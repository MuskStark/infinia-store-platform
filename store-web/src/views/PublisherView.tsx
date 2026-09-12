import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  api,
  ApiRequestError,
  type CatalogItem,
  type ListingDetail,
  type PublisherRelease,
  type SubmitResult,
  type UploadSession,
} from '../api/client';
import { Badge } from '@/components/ui/badge';
import ProgressBar from '../components/ProgressBar';
import StateChip from '../components/StateChip';
import SelectMenu from '../components/SelectMenu';
import PageHeader from '../components/PageHeader';
import { formatDate } from '../utils/format';
import {
  PluginManifestError,
  readPluginManifest,
  suggestSlug,
} from '../utils/pluginManifest';
import { usePublisherStore } from '../stores/publisher';
import { badgeToneClass, badgeBaseClass } from '../utils/badgeTone';
import { cn } from '@/lib/utils';

/** RFC 9457 problems carry stable codes; prefer the localized text. */
function useProblemText() {
  const { t } = useTranslation();
  return (e: unknown): string => {
    if (e instanceof ApiRequestError && e.code) {
      const key = `errors.${e.code}`;
      const localized = t(key);
      const text = localized !== key ? localized : (e.detail ?? e.message);
      // The generic title ("校验失败") hides which field failed — the server
      // keeps the concrete cause for validation errors (audit P1-6), so show it.
      if (
        e.code === 'validation_failed' &&
        e.detail &&
        !text.includes(e.detail)
      ) {
        return `${text}：${e.detail}`;
      }
      return text;
    }
    return e instanceof Error ? e.message : t('errors.server');
  };
}

type StepNum = 1 | 2 | 3;

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

/** infinia://plugin/ns/slug → PLUGIN — badges listing rows without extra API data. */
function typeFromCoordinate(coordinate: string): string {
  return (
    coordinate.replace(/^infinia:\/\//, '').split('/')[0] ?? ''
  ).toUpperCase();
}

/**
 * infinia://app/official/fengyu-host → { type: 'app', namespace: 'official',
 * slug: 'fengyu-host' }. The protocol separator leaves an empty first path
 * segment after the scheme, so split('/') must skip it before destructuring.
 */
function splitCoordinate(coordinate: string): {
  type: string;
  namespace: string;
  slug: string;
} {
  const [type = '', namespace = '', slug = ''] = coordinate
    .replace(/^infinia:\/\//, '')
    .split('/');
  return { type, namespace, slug };
}

/**
 * Product management buckets: where each listing's publishing flow stands.
 * Buckets: needs-attention (draft, rejected, changes requested),
 * in review (in-review, scanning), published, no releases yet.
 */
type ListingBucket = 'all' | 'action' | 'review' | 'published' | 'empty';
type ListingStatus = {
  listingId: string;
  bucket: Exclude<ListingBucket, 'all'>;
  release: PublisherRelease | null;
};

const ACTION_STATES = ['DRAFT', 'REJECTED', 'CHANGES_REQUESTED'];

function summarizeListing(
  listingId: string,
  releases: PublisherRelease[],
): ListingStatus {
  const byNewest = [...releases].sort((a, b) =>
    (b.createdAt ?? '').localeCompare(a.createdAt ?? ''),
  );
  const actionable = byNewest.find((r) => ACTION_STATES.includes(r.status));
  if (actionable) return { listingId, bucket: 'action', release: actionable };
  const inFlight = byNewest.find(
    (r) => r.status === 'IN_REVIEW' || r.status === 'SCANNING',
  );
  if (inFlight) return { listingId, bucket: 'review', release: inFlight };
  const published = byNewest.find((r) => r.status === 'PUBLISHED');
  if (published) return { listingId, bucket: 'published', release: published };
  return { listingId, bucket: 'empty', release: null };
}

const FILTER_GROUPS: { bucket: ListingBucket; titleKey: string }[] = [
  { bucket: 'all', titleKey: 'publisher.filterAll' },
  { bucket: 'action', titleKey: 'publisher.groupAction' },
  { bucket: 'review', titleKey: 'publisher.groupReview' },
  { bucket: 'published', titleKey: 'publisher.groupPublished' },
  { bucket: 'empty', titleKey: 'publisher.groupEmpty' },
];

/** Row action per bucket: the one thing the publisher should do next. */
function rowActionKey(bucket: ListingStatus['bucket']): string {
  if (bucket === 'action') return 'publisher.rowContinue';
  if (bucket === 'review') return 'publisher.rowReview';
  return 'publisher.rowNewRelease';
}

/**
 * Publisher center (design §8), split into two surfaces so management and the
 * release flow stop competing inside one card:
 *
 * - Manage home (full page width): every listing bucketed by where its flow
 *  stands, filterable, one row action per listing. "创建产品" lives here.
 * - Product context: after picking a listing, a focused 3-step wizard —
 *  版本信息 → 上传与校验 → 确认提交 — with the product name/version/channel
 *  pinned at the top, a ~640–720px form column and a 280px summary/checks
 *  rail on desktop. Steps unlock only when the previous one produced its
 *  artifact (a listing / a release + package); panels stay mounted so walking
 *  back never loses form input.
 */
export default function PublisherView() {
  const { t } = useTranslation();
  const problemText = useProblemText();
  const listings = usePublisherStore((s) => s.listings);
  const releasesByListing = usePublisherStore((s) => s.releasesByListing);
  const loadListings = usePublisherStore((s) => s.load);
  const refreshRelease = usePublisherStore((s) => s.refreshRelease);
  const loadReleases = usePublisherStore((s) => s.loadReleases);

  const [message, setMessage] = useState('');
  /** In-wizard feedback — actions happen down here, so success/failure must be
   * visible next to the buttons, not just at page top. */
  const [releaseMessage, setReleaseMessage] = useState('');
  const [messageError, setMessageError] = useState(false);
  const [releaseMessageError, setReleaseMessageError] = useState(false);

  /** Two surfaces: the management home vs. one listing's release wizard. */
  const [productMode, setProductMode] = useState(false);
  const [filter, setFilter] = useState<ListingBucket>('all');

  const [step, setStep] = useState<StepNum>(1);
  const STEP_DEFS: { n: StepNum; label: string }[] = useMemo(
    () => [
      { n: 1, label: 'publisher.step1' },
      { n: 2, label: 'publisher.step2' },
      { n: 3, label: 'publisher.step3' },
    ],
    [],
  );

  /** 命名空间下拉:我所在组织保留的命名空间(= 组织标识)。 */
  const [orgNamespaces, setOrgNamespaces] = useState<string[]>([]);
  const CUSTOM_NAMESPACE = '__custom__';
  const [useCustomNamespace, setUseCustomNamespace] = useState(false);

  const [orgForm, setOrgForm] = useState({ slug: '', name: '' });
  const [listingForm, setListingForm] = useState({
    namespace: '',
    slug: '',
    type: 'PLUGIN',
    name: '',
    summary: '',
    category: '',
    minBeeLevel: 0,
  });
  const [releaseForm, setReleaseForm] = useState({
    version: '',
    channel: 'stable',
    requiresHost: '',
  });
  const fileInput = useRef<HTMLInputElement | null>(null);
  const [packageName, setPackageName] = useState('');
  const [packageSize, setPackageSize] = useState(0);
  const [selectedListing, setSelectedListing] = useState<CatalogItem | null>(
    null,
  );
  const [selectedListingId, setSelectedListingId] = useState<string | null>(
    null,
  );
  const [currentRelease, setCurrentRelease] = useState<PublisherRelease | null>(
    null,
  );
  const [busy, setBusy] = useState(false);
  /** Upload/submit phase for honest feedback: the file only moves when the
   * confirm button is clicked (§6.5 timing). */
  const [submitPhase, setSubmitPhase] = useState<
    'idle' | 'uploading' | 'submitting'
  >('idle');
  /** pollStatus() gives up after a bounded number of tries; still-processing
   * is NOT success and NOT failure (§6.5 rule 5). */
  const [pollTimedOut, setPollTimedOut] = useState(false);

  /** The create-product panel opens from the manage-home header button; a
   * publisher with nothing to manage lands directly on it. */
  const [showCreatePanel, setShowCreatePanel] = useState(false);
  /** How the product basics get filled: package import vs. hand typing. */
  const [createMode, setCreateMode] = useState<'import' | 'manual'>('import');

  /** Package import: read the plugin's manifest.json so the listing basics and
   * the release version come from the package instead of hand-typing (the
   * hand-typed version was exactly how plugin.version-mismatch happened). */
  const packageInput = useRef<HTMLInputElement | null>(null);
  const [importedFrom, setImportedFrom] = useState<string | null>(null);

  const stepReachable = (n: StepNum): boolean => {
    if (n === 1) return true;
    if (n === 2) return currentRelease != null;
    return currentRelease != null && Boolean(packageName);
  };

  /** A step earns its checkmark once the next one has unlocked. */
  const stepDone = (n: StepNum): boolean =>
    n < 3 && stepReachable((n + 1) as StepNum);

  const goToStep = (n: StepNum) => {
    if (!stepReachable(n)) return;
    setStep(n);
  };

  const stepCircleClass = (n: StepNum): string => {
    if (step === n) return 'border-brand bg-brand text-[#18181b]';
    if (stepDone(n)) return 'border-accent/30 bg-accent/10 text-accent';
    return 'border-line bg-surface text-muted';
  };

  const stepLabelClass = (n: StepNum): string => {
    if (step === n) return 'text-accent';
    if (stepReachable(n)) return 'text-ink';
    return 'text-muted';
  };

  async function onImportPackage() {
    const file = packageInput.current?.files?.[0];
    if (!file) return;
    setImportedFrom(null);
    try {
      const manifest = await readPluginManifest(file);
      setListingForm((form) => ({
        ...form,
        type: 'PLUGIN',
        slug: suggestSlug(manifest.id),
        name: manifest.name,
        summary: manifest.description,
        category: manifest.category,
      }));
      setReleaseForm((form) => ({
        ...form,
        version: manifest.version,
        requiresHost: manifest.engines ?? form.requiresHost,
      }));
      setImportedFrom(`${manifest.id} · v${manifest.version}`);
      setMessage('');
      setShowCreatePanel(true);
    } catch (e) {
      setMessageError(true);
      setMessage(
        e instanceof PluginManifestError
          ? t(`publisher.import.${e.message}`)
          : t('errors.server'),
      );
    } finally {
      if (packageInput.current) packageInput.current.value = '';
    }
  }

  /** Drag-and-drop mirrors the hidden file input (same pattern as step 2). */
  function onImportDrop(event: React.DragEvent) {
    const files = event.dataTransfer?.files;
    if (files?.length && packageInput.current) {
      packageInput.current.files = files;
      void onImportPackage();
    }
  }

  const [listingStatuses, setListingStatuses] = useState<
    Record<string, ListingStatus>
  >({});
  const [statusesReady, setStatusesReady] = useState(false);
  const hydrating = useRef(false);

  async function hydrateListingStatuses() {
    if (hydrating.current) return;
    hydrating.current = true;
    try {
      await Promise.all(
        usePublisherStore.getState().listings.map(async (listing) => {
          try {
            const detail = await api.get<ListingDetail>(
              `/api/v1/listings/${listing.namespace}/${listing.slug}`,
            );
            await loadReleases(detail.listingId);
            const releaseList =
              usePublisherStore.getState().releasesByListing[
                detail.listingId
              ] ?? [];
            setListingStatuses((current) => ({
              ...current,
              [listing.coordinate]: summarizeListing(
                detail.listingId,
                releaseList,
              ),
            }));
          } catch {
            // Listing unavailable — without releases it lands in the trailing group.
            setListingStatuses((current) => ({
              ...current,
              [listing.coordinate]: {
                listingId: '',
                bucket: 'empty',
                release: null,
              },
            }));
          }
        }),
      );
      setStatusesReady(true);
    } finally {
      hydrating.current = false;
    }
  }

  const filterCounts = useMemo(() => {
    const counts: Record<ListingBucket, number> = {
      all: listings.length,
      action: 0,
      review: 0,
      published: 0,
      empty: 0,
    };
    for (const listing of listings) {
      counts[listingStatuses[listing.coordinate]?.bucket ?? 'empty'] += 1;
    }
    return counts;
  }, [listings, listingStatuses]);

  const managedListings = useMemo(
    () =>
      listings
        .filter(
          (listing) =>
            filter === 'all' ||
            (listingStatuses[listing.coordinate]?.bucket ?? 'empty') === filter,
        )
        .map((listing) => ({
          listing,
          status: listingStatuses[listing.coordinate],
        })),
    [listings, listingStatuses, filter],
  );

  /** Latest PUBLISHED release version per listing, for the manage table. */
  function latestPublishedLabel(status?: ListingStatus): string {
    return status?.release && status.bucket === 'published'
      ? `v${status.release.version}`
      : '—';
  }

  /**
   * The backend rework path (audit P1-3) accepts uploads for REJECTED and
   * CHANGES_REQUESTED releases and moves them back to DRAFT, so the upload
   * panel must light up for those states too — not just DRAFT.
   */
  const UPLOADABLE_STATES = ['DRAFT', 'REJECTED', 'CHANGES_REQUESTED'];
  const canUploadCurrent =
    currentRelease != null && UPLOADABLE_STATES.includes(currentRelease.status);

  /** Published / withdrawn releases are history — they leave via withdraw (yank)
   * or admin actions, not by publisher deletion. */
  const NON_DELETABLE_STATES = [
    'PUBLISHED',
    'DEPRECATED',
    'YANKED',
    'QUARANTINED',
  ];
  const canDeleteCurrent =
    currentRelease != null &&
    !NON_DELETABLE_STATES.includes(currentRelease.status);

  async function deleteCurrentRelease() {
    const release = currentRelease;
    if (!release) return;
    if (
      !window.confirm(
        t('publisher.deleteReleaseConfirm', { version: release.version }),
      )
    )
      return;
    setBusy(true);
    try {
      await api.delete(`/api/v1/publisher/releases/${release.releaseId}`);
      setMessageError(false);
      setMessage(t('publisher.releaseDeleted'));
      setReleaseMessage('');
      setCurrentRelease(null);
      setPackageName('');
      setPackageSize(0);
      if (fileInput.current) fileInput.current.value = '';
      if (selectedListingId) {
        await loadReleases(selectedListingId).catch(() => {});
      }
      void hydrateListingStatuses();
      setStep(1);
    } catch (e) {
      setReleaseMessageError(true);
      setReleaseMessage(problemText(e));
    } finally {
      setBusy(false);
    }
  }

  async function createOrg(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const created = await api.post<{ slug: string }>(
        '/api/v1/organizations',
        orgForm,
      );
      setMessageError(false);
      setMessage(t('publisher.orgCreated'));
      await loadOrgNamespaces();
      setListingForm((form) => ({
        ...form,
        namespace: created?.slug ?? orgForm.slug,
      }));
      setUseCustomNamespace(false);
      setOrgForm({ slug: '', name: '' });
    } catch (e) {
      setMessageError(true);
      setMessage(problemText(e));
    } finally {
      setBusy(false);
    }
  }

  async function createListing(event: React.FormEvent) {
    event.preventDefault();
    // Without a namespace the request can only fail server-side: namespaces
    // exist only via organization creation, so say so instead of a round trip.
    if (!listingForm.namespace.trim()) {
      setMessageError(true);
      setMessage(t('publisher.namespaceRequired'));
      return;
    }
    setBusy(true);
    try {
      await api.post('/api/v1/publisher/listings', {
        ...listingForm,
        tags: [],
      });
      // Light selection: a fresh listing has no releases, so skip the releases
      // fetch and walk into the wizard — creating a release there resolves the
      // listing UUID on demand.
      setSelectedListing({
        coordinate: `infinia://${listingForm.type.toLowerCase()}/${listingForm.namespace}/${listingForm.slug}`,
        name: listingForm.name,
      } as CatalogItem);
      setSelectedListingId(null);
      setCurrentRelease(null);
      setProductMode(true);
      setStep(1);
      setShowCreatePanel(false);
      await loadListings();
      setMessageError(false);
      setMessage(t('publisher.listingCreated'));
    } catch (e) {
      setMessageError(true);
      setMessage(problemText(e));
    } finally {
      setBusy(false);
    }
  }

  async function createRelease(event: React.FormEvent) {
    event.preventDefault();
    const selected = selectedListing;
    if (!selected?.coordinate) return;
    setBusy(true);
    setMessage('');
    setReleaseMessage('');
    try {
      // Resolve the listing UUID from the public detail endpoint.
      const { namespace, slug } = splitCoordinate(selected.coordinate);
      const detail = await api.get<ListingDetail>(
        `/api/v1/listings/${namespace}/${slug}`,
      );
      const release = await api.post<PublisherRelease>(
        `/api/v1/publisher/listings/${detail.listingId}/releases`,
        releaseForm,
      );
      setSelectedListingId(detail.listingId);
      setCurrentRelease(release);
      setPackageName('');
      setPackageSize(0);
      if (fileInput.current) fileInput.current.value = '';
      await loadReleases(detail.listingId);
      setReleaseMessageError(false);
      setReleaseMessage(t('publisher.releaseCreated'));
      setStep(2);
    } catch (e) {
      setReleaseMessageError(true);
      setReleaseMessage(problemText(e));
    } finally {
      setBusy(false);
    }
  }

  function onPackageChange() {
    const file = fileInput.current?.files?.[0];
    setPackageName(file?.name ?? '');
    setPackageSize(file?.size ?? 0);
  }

  /** Drag-and-drop mirrors the hidden file input, so both paths share one state. */
  function onPackageDrop(event: React.DragEvent) {
    const files = event.dataTransfer?.files;
    if (files?.length && fileInput.current) {
      fileInput.current.files = files;
      onPackageChange();
    }
  }

  async function uploadAndSubmit() {
    const file = fileInput.current?.files?.[0];
    const release = currentRelease;
    if (!file || !release) return;
    setBusy(true);
    setReleaseMessage('');
    setPollTimedOut(false);
    setSubmitPhase('uploading');
    try {
      const session = await api.post<UploadSession>(
        `/api/v1/publisher/releases/${release.releaseId}/uploads`,
        { filename: file.name },
      );
      await api.putRaw(session.uploadUrl, await file.arrayBuffer());
      setSubmitPhase('submitting');
      const result = await api.post<SubmitResult>(
        `/api/v1/publisher/releases/${release.releaseId}/submit`,
      );
      const timedOut = await pollStatus();
      const finalStatus =
        usePublisherStore.getState().releases[release.releaseId]?.status ??
        result.status;
      setReleaseMessageError(false);
      if (
        timedOut ||
        finalStatus === 'SCANNING' ||
        finalStatus === 'UPLOADING'
      ) {
        setPollTimedOut(true);
        setReleaseMessage(t('publisher.stillProcessing'));
      } else if (finalStatus === 'PUBLISHED') {
        setReleaseMessage(t('publisher.publishedDone'));
      } else if (finalStatus === 'IN_REVIEW') {
        setReleaseMessage(t('publisher.waitingReview'));
      } else {
        setReleaseMessage(
          t('publisher.submittedStatus', { status: t(`state.${finalStatus}`) }),
        );
      }
      void hydrateListingStatuses();
    } catch (e) {
      setReleaseMessageError(true);
      setReleaseMessage(problemText(e));
    } finally {
      setBusy(false);
      setSubmitPhase('idle');
    }
  }

  /** @returns true when the bounded poll ran out before a terminal status. */
  async function pollStatus(): Promise<boolean> {
    const releaseId = currentRelease?.releaseId;
    if (!releaseId) return false;
    let timedOut = true;
    for (let i = 0; i < 20; i++) {
      await refreshRelease(releaseId);
      const status = usePublisherStore.getState().releases[releaseId]?.status;
      if (status !== 'SCANNING' && status !== 'UPLOADING') {
        timedOut = false;
        break;
      }
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    setCurrentRelease(
      usePublisherStore.getState().releases[releaseId] ?? currentRelease,
    );
    return timedOut;
  }

  /** Manual status check for the still-processing state (§6.5 rule 5). */
  async function refreshCurrentStatus() {
    const releaseId = currentRelease?.releaseId;
    if (!releaseId) return;
    setBusy(true);
    try {
      const timedOut = await pollStatus();
      setPollTimedOut(timedOut);
      setReleaseMessageError(false);
      setReleaseMessage(
        timedOut
          ? t('publisher.stillProcessing')
          : t('publisher.submittedStatus', {
              status: t(
                `state.${usePublisherStore.getState().releases[releaseId]?.status ?? ''}`,
              ),
            }),
      );
      void hydrateListingStatuses();
    } catch (e) {
      setReleaseMessageError(true);
      setReleaseMessage(problemText(e));
    } finally {
      setBusy(false);
    }
  }

  async function selectListing(listing: CatalogItem) {
    setSelectedListing(listing);
    setSelectedListingId(null);
    setCurrentRelease(null);
    setMessage('');
    setReleaseMessage('');
    setPackageName('');
    setPackageSize(0);
    setProductMode(true);
    setStep(1);
    // Resolve the listing UUID, then load its releases (incl. DRAFTs) so an
    // interrupted draft can be resumed — the upload area keys off currentRelease.
    try {
      const { namespace, slug } = splitCoordinate(listing.coordinate);
      const detail = await api.get<ListingDetail>(
        `/api/v1/listings/${namespace}/${slug}`,
      );
      setSelectedListingId(detail.listingId);
      await loadReleases(detail.listingId);
      const draft = usePublisherStore
        .getState()
        .releasesByListing[detail.listingId]?.find((r) => r.status === 'DRAFT');
      if (draft) {
        setCurrentRelease(draft);
        setReleaseMessageError(false);
        setReleaseMessage(t('publisher.draftResumed'));
        // "继续草稿" is the point of picking this listing — land directly on
        // the upload step instead of making the user click the draft again.
        setStep(2);
      }
    } catch {
      /* detail load failure leaves the wizard usable for new releases */
      setMessageError(true);
      setMessage(t('publisher.releasesUnavailable'));
    }
  }

  function backToManage() {
    setProductMode(false);
    setStep(1);
    setPackageName('');
    setPackageSize(0);
    setReleaseMessage('');
    setPollTimedOut(false);
    setSubmitPhase('idle');
    void hydrateListingStatuses();
  }

  /**
   * Any release row can be selected: DRAFT to resume the upload, REJECTED /
   * CHANGES_REQUESTED to read the scan findings and re-upload (the backend
   * flips those back to DRAFT on upload). Terminal states show their history.
   */
  function pickRelease(release: PublisherRelease) {
    setMessage('');
    setReleaseMessage('');
    setPackageName('');
    setPackageSize(0);
    if (fileInput.current) fileInput.current.value = '';
    setCurrentRelease(release);
    setStep(2);
    // The listing-scoped list omits scan findings; the single-release detail
    // carries them, so a rejected row can explain itself after a reload.
    if (release.findings?.length) return;
    void (async () => {
      try {
        await refreshRelease(release.releaseId);
        setCurrentRelease(
          usePublisherStore.getState().releases[release.releaseId] ?? release,
        );
      } catch {
        /* keep the row-level data if the detail fetch fails */
      }
    })();
  }

  async function loadOrgNamespaces() {
    try {
      const orgs = await api.get<{ slug: string }[]>('/api/v1/organizations');
      setOrgNamespaces(orgs.map((o) => o.slug));
      setListingForm((form) => {
        if (!form.namespace && orgs.length) {
          return { ...form, namespace: orgs[0].slug };
        }
        return form;
      });
    } catch {
      /* 下拉加载失败时回退手动输入 */
    }
  }

  function onNamespaceChange(value: string) {
    if (value === CUSTOM_NAMESPACE) {
      setUseCustomNamespace(true);
      setListingForm((form) => ({ ...form, namespace: '' }));
    } else {
      setUseCustomNamespace(false);
      setListingForm((form) => ({ ...form, namespace: value }));
    }
  }

  useEffect(() => {
    void loadOrgNamespaces();
    void (async () => {
      await loadListings();
      setShowCreatePanel(!usePublisherStore.getState().listings.length);
      void hydrateListingStatuses();
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const LISTING_TYPES = ['APP', 'PLUGIN', 'SKILL', 'MCP', 'FLOW'] as const;
  const BEE_LEVEL_OPTIONS = [0, 1, 2, 3, 4].map((level) => ({
    value: level,
    label:
      level === 0
        ? t('publisher.beeLevelPublic')
        : `${t(`beeLevel.${level}`)} · Lv${level}+`,
  }));
  const CHANNEL_OPTIONS = [
    { value: 'stable', label: t('channel.stable') },
    { value: 'beta', label: t('channel.beta') },
  ];

  /** Row hint on the right edge of each release row. */
  function releaseRowHint(status: string): string {
    if (status === 'DRAFT') return t('publisher.clickToResume');
    if (status === 'REJECTED' || status === 'CHANGES_REQUESTED') {
      return t('publisher.clickToInspect');
    }
    return '';
  }

  /* ---------- Manage home: product table, filters, create panel ---------- */

  if (!productMode) {
    return (
      <div className="page-shell space-y-6">
        <PageHeader
          title={t('publisher.title')}
          subtitle={t('publisher.subtitleManage')}
          actions={
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => setShowCreatePanel(!showCreatePanel)}
            >
              {t('publisher.createListingAction')}
            </button>
          }
        />

        {message && (
          <p
            className={cn('alert', messageError ? 'alert-error' : 'alert-info')}
            role="status"
          >
            {message}
          </p>
        )}

        {/* Filter chips reuse the status buckets as management entry points. */}
        <div className="flex flex-wrap items-center gap-1.5" role="tablist">
          {FILTER_GROUPS.map((group) => (
            <button
              key={group.bucket}
              type="button"
              role="tab"
              aria-selected={filter === group.bucket}
              className={cn(
                'tap-compact rounded-full px-3.5 py-2 text-sm font-medium transition-colors',
                filter === group.bucket
                  ? 'bg-surface text-ink shadow-sm ring-1 ring-line'
                  : 'text-muted hover:bg-surface-muted hover:text-ink',
              )}
              onClick={() => setFilter(group.bucket)}
            >
              {filter === group.bucket && (
                <span
                  className="mr-1.5 inline-block size-1.5 rounded-full bg-brand align-middle"
                  aria-hidden="true"
                />
              )}
              {t(group.titleKey)}
              <span className="ml-1.5 text-xs text-muted">
                ({filterCounts[group.bucket]})
              </span>
            </button>
          ))}
        </div>

        <div className="table-card">
          <table>
            <thead>
              <tr>
                <th>{t('publisher.name')}</th>
                <th>{t('publisher.type')}</th>
                <th>{t('publisher.latestPublished')}</th>
                <th>{t('publisher.ongoingStatus')}</th>
                <th>{t('publisher.updatedAt')}</th>
                <th aria-label={t('publisher.status')} />
              </tr>
            </thead>
            <tbody>
              {!managedListings.length ? (
                <tr>
                  <td colSpan={6} className="py-8 text-center text-muted">
                    {statusesReady
                      ? t('publisher.noListings')
                      : t('publisher.loadingListings')}
                  </td>
                </tr>
              ) : (
                managedListings.map(({ listing, status }) => (
                  <tr key={listing.coordinate}>
                    <td>
                      <button
                        type="button"
                        className="tap-compact font-medium hover:text-accent"
                        onClick={() => void selectListing(listing)}
                      >
                        {listing.name}
                      </button>
                      <code className="mt-0.5 block text-xs text-muted">
                        {listing.coordinate}
                      </code>
                    </td>
                    <td>
                      <Badge
                        variant="outline"
                        className={cn(badgeBaseClass, badgeToneClass.muted)}
                      >
                        {typeFromCoordinate(listing.coordinate)}
                      </Badge>
                    </td>
                    <td className="text-muted">
                      {latestPublishedLabel(status)}
                    </td>
                    <td>
                      {status?.release ? (
                        <span className="flex items-center gap-1.5">
                          <StateChip status={status.release.status} />
                          <span className="text-xs text-muted">
                            v{status.release.version}
                          </span>
                        </span>
                      ) : (
                        <span className="text-muted">—</span>
                      )}
                    </td>
                    <td className="text-muted">
                      {formatDate(status?.release?.createdAt ?? '') || '—'}
                    </td>
                    <td className="text-right">
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm tap-compact"
                        onClick={() => void selectListing(listing)}
                      >
                        {status
                          ? t(rowActionKey(status.bucket))
                          : t('publisher.rowNewRelease')}
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* Create product: import from package or hand-type, on demand. */}
        <div hidden={!showCreatePanel} className="card space-y-5 p-6">
          <div>
            <h2 className="text-lg font-semibold">
              {t('publisher.createNewListing')}
            </h2>
            <div className="mt-3 flex gap-2" role="tablist">
              {(['import', 'manual'] as const).map((mode) => (
                <button
                  key={mode}
                  type="button"
                  role="tab"
                  aria-selected={createMode === mode}
                  className={cn(
                    'tap-compact rounded-lg border px-3 py-2 text-sm font-medium transition-colors',
                    createMode === mode
                      ? 'border-accent bg-accent/5 text-accent'
                      : 'border-line text-muted hover:text-ink',
                  )}
                  onClick={() => setCreateMode(mode)}
                >
                  {t(`publisher.createMode_${mode}`)}
                </button>
              ))}
            </div>
          </div>

          {createMode === 'import' && (
            <div>
              {/* 插件包导入:基础信息与版本号从 manifest.json 读取,不必手工填写。 */}
              <input
                ref={packageInput}
                type="file"
                accept=".fyp,.zip"
                className="hidden"
                onChange={() => void onImportPackage()}
              />
              <button
                type="button"
                className="flex w-full flex-col items-center gap-1 rounded-xl border-2 border-dashed border-line px-6 py-5 text-center transition-colors hover:border-accent/60 hover:bg-accent/5"
                onClick={() => packageInput.current?.click()}
                onDragOver={(e) => e.preventDefault()}
                onDrop={(e) => {
                  e.preventDefault();
                  onImportDrop(e);
                }}
              >
                <svg
                  width="20"
                  height="20"
                  viewBox="0 0 24 24"
                  fill="none"
                  aria-hidden="true"
                  className="text-muted"
                >
                  <path
                    d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                  <path
                    d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    strokeLinecap="round"
                  />
                </svg>
                <span className="text-sm font-medium">
                  {t('publisher.importHint')}
                </span>
                <span className="text-xs text-muted">
                  {t('publisher.importHintDetail')}
                </span>
              </button>
              {importedFrom && (
                <p className="mt-2 text-xs text-accent" role="status">
                  {t('publisher.importDone', { source: importedFrom })}
                </p>
              )}
            </div>
          )}

          {/* 组织表单只在还没有命名空间时出现;创建后自动预选命名空间。 */}
          {!orgNamespaces.length && (
            <div className="rounded-lg bg-surface-muted p-4">
              <h3 className="text-sm font-semibold">
                {t('publisher.createOrg')}
              </h3>
              <p className="mt-0.5 text-xs text-muted">
                {t('publisher.namespaceNone')}
              </p>
              <form
                className="mt-3 grid gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto]"
                onSubmit={createOrg}
              >
                <label className="block text-sm">
                  <span className="mb-1 block">{t('publisher.orgSlug')}</span>
                  {/* pattern 里的短横线必须转义:HTML 用 v 标志编译 pattern,
            [a-z0-9-] 在 v 模式下非法 → 整条约束被浏览器静默忽略,
            大写 slug 就会漏到服务端变成一句没有细节的"校验失败"。 */}
                  <input
                    value={orgForm.slug}
                    onChange={(e) =>
                      setOrgForm({ ...orgForm, slug: e.target.value })
                    }
                    required
                    pattern="[a-z0-9][a-z0-9\-]{0,62}"
                    className="input"
                  />
                  <span className="mt-1 block text-xs text-muted">
                    {t('publisher.orgSlugHint')}
                  </span>
                </label>
                <label className="block text-sm">
                  <span className="mb-1 block">{t('publisher.orgName')}</span>
                  <input
                    value={orgForm.name}
                    onChange={(e) =>
                      setOrgForm({ ...orgForm, name: e.target.value })
                    }
                    className="input"
                  />
                </label>
                <button
                  type="submit"
                  disabled={busy}
                  className="btn btn-primary self-end whitespace-nowrap"
                >
                  {t('publisher.createOrgAction')}
                </button>
              </form>
            </div>
          )}

          <form className="grid gap-4 sm:grid-cols-2" onSubmit={createListing}>
            <label className="block text-sm">
              <span className="mb-1 block">{t('publisher.namespace')}</span>
              {!useCustomNamespace ? (
                <SelectMenu
                  value={listingForm.namespace}
                  options={[
                    ...orgNamespaces.map((ns) => ({ value: ns, label: ns })),
                    {
                      value: CUSTOM_NAMESPACE,
                      label: t('publisher.namespaceCustom'),
                    },
                  ]}
                  ariaLabel={t('publisher.namespace')}
                  onValueChange={(v) => onNamespaceChange(String(v))}
                />
              ) : (
                <span className="flex gap-2">
                  <input
                    value={listingForm.namespace}
                    onChange={(e) =>
                      setListingForm({
                        ...listingForm,
                        namespace: e.target.value,
                      })
                    }
                    required
                    placeholder={t('publisher.namespace')}
                    className="input min-w-0 flex-1"
                  />
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm tap-compact mt-0.5 shrink-0 self-start whitespace-nowrap"
                    onClick={() => setUseCustomNamespace(false)}
                  >
                    {t('publisher.namespaceBackToList')}
                  </button>
                </span>
              )}
            </label>
            <label className="block text-sm">
              <span className="mb-1 block">{t('common.type')}</span>
              <SelectMenu
                value={listingForm.type}
                options={LISTING_TYPES.map((type) => ({
                  value: type,
                  label: t(`type.${type}`),
                }))}
                ariaLabel={t('common.type')}
                onValueChange={(v) =>
                  setListingForm({ ...listingForm, type: String(v) })
                }
              />
            </label>
            <label className="block text-sm">
              <span className="mb-1 block">{t('publisher.slug')}</span>
              <input
                value={listingForm.slug}
                onChange={(e) =>
                  setListingForm({ ...listingForm, slug: e.target.value })
                }
                required
                pattern="[a-z0-9][a-z0-9\-]{0,62}"
                className="input"
              />
            </label>
            <label className="block text-sm">
              <span className="mb-1 block">{t('publisher.name')}</span>
              <input
                value={listingForm.name}
                onChange={(e) =>
                  setListingForm({ ...listingForm, name: e.target.value })
                }
                required
                className="input"
              />
            </label>
            <label className="block text-sm sm:col-span-2">
              <span className="mb-1 block">{t('publisher.summary')}</span>
              <input
                value={listingForm.summary}
                onChange={(e) =>
                  setListingForm({ ...listingForm, summary: e.target.value })
                }
                className="input"
              />
            </label>
            <div className="text-sm sm:col-span-2">
              <span className="mb-1 block">{t('publisher.minBeeLevel')}</span>
              <SelectMenu
                value={listingForm.minBeeLevel}
                options={BEE_LEVEL_OPTIONS}
                ariaLabel={t('publisher.minBeeLevel')}
                onValueChange={(v) =>
                  setListingForm({ ...listingForm, minBeeLevel: Number(v) })
                }
              />
              <p className="mt-1 text-xs text-muted">
                {t('publisher.minBeeLevelHint')}
              </p>
            </div>
            <div className="flex justify-end sm:col-span-2">
              <button
                type="submit"
                disabled={busy}
                className="btn btn-primary whitespace-nowrap"
              >
                {t('publisher.createListingAction')}
              </button>
            </div>
          </form>
        </div>
      </div>
    );
  }

  /* ---------- Product context: the focused release wizard ---------- */

  return (
    <div className="page-shell space-y-6">
      <button
        type="button"
        className="btn btn-ghost btn-sm tap-compact -ml-2"
        onClick={backToManage}
      >
        ← {t('publisher.backToManage')}
      </button>

      {/* Pinned context: product identity survives every step switch. */}
      <header className="card flex flex-wrap items-center justify-between gap-x-6 gap-y-3 p-5">
        <div className="min-w-0">
          <h1 className="truncate text-2xl font-bold tracking-tight">
            {selectedListing?.name}
          </h1>
          <code className="mt-1 block truncate text-sm text-muted">
            {selectedListing?.coordinate}
          </code>
        </div>
        {currentRelease && (
          <div className="flex flex-wrap items-center gap-2">
            <StateChip status={currentRelease.status} />
            <Badge
              variant="outline"
              className={cn(badgeBaseClass, badgeToneClass.muted)}
            >
              v{currentRelease.version}
            </Badge>
            <Badge
              variant="outline"
              className={cn(badgeBaseClass, badgeToneClass.muted)}
            >
              {t(`channel.${currentRelease.channel}`)}
            </Badge>
          </div>
        )}
      </header>

      {message && (
        <p
          className={cn('alert', messageError ? 'alert-error' : 'alert-info')}
          role="status"
        >
          {message}
        </p>
      )}

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_260px] min-[1200px]:grid-cols-[160px_minmax(0,1fr)_260px]">
        {/* 步骤指示器:圆点 + 连线,只有已解锁的步骤可以点击回退。 */}
        <ol
          className="flex flex-wrap items-center gap-2 lg:col-span-2 min-[1200px]:col-span-1 min-[1200px]:flex-col min-[1200px]:items-stretch min-[1200px]:self-start"
          aria-label={t('publisher.stepNav')}
        >
          {STEP_DEFS.map((def, index) => (
            <Fragment key={def.n}>
              <li>
                <button
                  type="button"
                  className="tap-compact flex items-center gap-2.5 rounded-lg py-1 pr-1 text-left disabled:cursor-not-allowed"
                  disabled={!stepReachable(def.n)}
                  aria-current={step === def.n ? 'step' : undefined}
                  onClick={() => goToStep(def.n)}
                >
                  <span
                    className={cn(
                      'grid size-7 shrink-0 place-items-center rounded-full border text-xs font-semibold transition-colors',
                      stepCircleClass(def.n),
                    )}
                  >
                    {stepDone(def.n) ? (
                      <svg
                        viewBox="0 0 16 16"
                        className="size-3.5"
                        fill="none"
                        aria-hidden="true"
                      >
                        <path
                          d="M3 8.5 6.5 12 13 4.5"
                          stroke="currentColor"
                          strokeWidth="2"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                        />
                      </svg>
                    ) : (
                      <>{def.n}</>
                    )}
                  </span>
                  <span
                    className={cn('text-sm font-medium', stepLabelClass(def.n))}
                  >
                    {t(def.label)}
                  </span>
                </button>
              </li>
              {index < STEP_DEFS.length - 1 && (
                <li
                  aria-hidden="true"
                  className={cn(
                    'mx-2 h-px flex-1 min-[1200px]:ml-3.5 min-[1200px]:h-8 min-[1200px]:w-px min-[1200px]:flex-none',
                    stepDone(def.n) ? 'bg-accent/40' : 'bg-line',
                  )}
                />
              )}
            </Fragment>
          ))}
        </ol>

        <div className="card min-w-0 p-6">
          {/* 第 1 步:版本信息 —— 新建版本,或点击草稿/被退回的版本继续 */}
          <section hidden={step !== 1}>
            <header className="mb-4">
              <h2 className="text-base font-semibold">
                {t('publisher.newRelease')}
              </h2>
              <p className="mt-1 text-sm text-muted">
                {t('publisher.step2Hint')}
              </p>
            </header>

            {selectedListingId ? (
              <div className="mb-5">
                <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">
                  {t('publisher.releases')}
                </h3>
                {!releasesByListing[selectedListingId]?.length ? (
                  <p className="py-2 text-sm text-muted">
                    {t('publisher.noReleases')}
                  </p>
                ) : (
                  <ul className="divide-y divide-line">
                    {(releasesByListing[selectedListingId] ?? []).map(
                      (release) => (
                        <li key={release.releaseId}>
                          <button
                            type="button"
                            className={cn(
                              'tap-compact -mx-2 flex w-full flex-wrap items-center gap-3 rounded-lg px-2 py-2.5 text-left text-sm transition-colors hover:bg-surface-muted/70',
                              currentRelease?.releaseId === release.releaseId &&
                                'text-accent',
                            )}
                            onClick={() => pickRelease(release)}
                          >
                            <code className="font-semibold">
                              v{release.version}
                            </code>
                            <Badge
                              variant="outline"
                              className={cn(
                                badgeBaseClass,
                                badgeToneClass.muted,
                              )}
                            >
                              {t(`channel.${release.channel}`)}
                            </Badge>
                            <StateChip status={release.status} />
                            <span className="ml-auto text-xs text-muted">
                              {releaseRowHint(release.status) ||
                                formatDate(release.createdAt)}
                            </span>
                          </button>
                        </li>
                      ),
                    )}
                  </ul>
                )}
              </div>
            ) : null}

            <form
              className="max-w-2xl border-t border-line pt-5"
              onSubmit={createRelease}
            >
              <div className="grid gap-4 sm:grid-cols-3">
                <label className="block text-sm">
                  <span className="mb-1 block">{t('publisher.version')}</span>
                  <input
                    value={releaseForm.version}
                    onChange={(e) =>
                      setReleaseForm({
                        ...releaseForm,
                        version: e.target.value,
                      })
                    }
                    required
                    placeholder="1.0.0"
                    className="input"
                  />
                </label>
                <label className="block text-sm">
                  <span className="mb-1 block">{t('publisher.channel')}</span>
                  <SelectMenu
                    value={releaseForm.channel}
                    options={CHANNEL_OPTIONS}
                    ariaLabel={t('publisher.channel')}
                    onValueChange={(v) =>
                      setReleaseForm({ ...releaseForm, channel: String(v) })
                    }
                  />
                </label>
                <label className="block text-sm">
                  <span className="mb-1 block">
                    {t('publisher.requiresHost')}
                  </span>
                  <input
                    value={releaseForm.requiresHost}
                    onChange={(e) =>
                      setReleaseForm({
                        ...releaseForm,
                        requiresHost: e.target.value,
                      })
                    }
                    placeholder=">=4.0.0 <5.0.0"
                    className="input"
                  />
                </label>
              </div>
              <div className="mt-4 flex justify-end">
                <button
                  type="submit"
                  disabled={busy}
                  className="btn btn-primary whitespace-nowrap"
                >
                  {t('publisher.createReleaseAction')}
                </button>
              </div>
            </form>
          </section>

          {/* 第 2 步:上传与校验。阅读宽度受限;桌面端右侧是包信息栏。 */}
          <section hidden={step !== 2}>
            <header className="mb-4">
              <h2 className="text-base font-semibold">
                {t('publisher.step2')}
              </h2>
              <p className="mt-1 text-sm text-muted">
                {t('publisher.uploadHint')}
              </p>
            </header>

            <div className="min-w-0">
              <div>
                {currentRelease ? (
                  <div className="space-y-3">
                    {currentRelease.status !== 'DRAFT' && canUploadCurrent && (
                      <p className="alert alert-info">
                        {t('publisher.reworkHint')}
                      </p>
                    )}

                    {!canUploadCurrent &&
                      currentRelease.status === 'SCANNING' && (
                        <div className="space-y-2">
                          <ProgressBar />
                          <p className="text-xs text-muted">
                            {t('publisher.scanningHint')}
                          </p>
                        </div>
                      )}

                    {canUploadCurrent && (
                      <div className="space-y-3">
                        {/* Same hidden-input + styled picker pattern as the admin app-release
               upload, so both package uploaders render identically. */}
                        <input
                          ref={fileInput}
                          type="file"
                          className="hidden"
                          onChange={onPackageChange}
                        />
                        <button
                          type="button"
                          className="flex w-full flex-col items-center gap-2 rounded-xl border-2 border-dashed border-line px-6 py-8 text-center transition-colors hover:border-accent/60 hover:bg-accent/5"
                          onClick={() => fileInput.current?.click()}
                          onDragOver={(e) => e.preventDefault()}
                          onDrop={(e) => {
                            e.preventDefault();
                            onPackageDrop(e);
                          }}
                        >
                          <svg
                            width="24"
                            height="24"
                            viewBox="0 0 24 24"
                            fill="none"
                            aria-hidden="true"
                            className="text-muted"
                          >
                            <path
                              d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5"
                              stroke="currentColor"
                              strokeWidth="1.5"
                              strokeLinecap="round"
                              strokeLinejoin="round"
                            />
                            <path
                              d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"
                              stroke="currentColor"
                              strokeWidth="1.5"
                              strokeLinecap="round"
                            />
                          </svg>
                          <span className="text-sm font-medium">
                            {t('publisher.uploadDropHint')}
                          </span>
                          <span className="text-xs text-muted">
                            {t('publisher.uploadHint')}
                          </span>
                        </button>
                        {packageName && (
                          <p className="text-sm" role="status">
                            {t('publisher.packageSelected', {
                              name: packageName,
                              size: formatSize(packageSize),
                            })}
                          </p>
                        )}
                        {!packageName && (
                          <p className="text-xs text-muted">
                            {t('publisher.packagePending')}
                          </p>
                        )}
                        <div className="flex flex-wrap items-center justify-end gap-3">
                          <button
                            type="button"
                            className="btn btn-secondary"
                            onClick={() => setStep(1)}
                          >
                            ← {t('publisher.backToReleases')}
                          </button>
                          <button
                            type="button"
                            className="btn btn-primary"
                            disabled={!packageName}
                            onClick={() => setStep(3)}
                          >
                            {t('common.next')}
                          </button>
                        </div>
                      </div>
                    )}

                    {Boolean(currentRelease.findings?.length) && (
                      <div className="mt-4">
                        <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">
                          {t('publisher.lastFindings')}
                        </p>
                        <ul className="space-y-1 text-sm">
                          {(currentRelease.findings ?? []).map((finding) => (
                            <li key={finding.rule} className="card p-2">
                              <Badge
                                variant="outline"
                                className={cn(
                                  badgeBaseClass,
                                  finding.severity === 'ERROR' ||
                                    finding.severity === 'CRITICAL'
                                    ? badgeToneClass.danger
                                    : badgeToneClass.muted,
                                )}
                              >
                                {finding.severity}
                              </Badge>
                              {` ${finding.rule} — ${finding.message}`}
                            </li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </div>
                ) : null}
              </div>

              {/* Summary rail: what will be submitted, visible while picking the file. */}

            </div>
          </section>

          {/* 第 3 步:确认提交 —— 发布摘要 + 检查结果,一个主动作。 */}
          <section hidden={step !== 3}>
            <header className="mb-4">
              <h2 className="text-base font-semibold">
                {t('publisher.confirmTitle')}
              </h2>
              <p className="mt-1 text-sm text-muted">
                {t('publisher.confirmHint')}
              </p>
            </header>

            <div className="min-w-0">
              <div className="max-w-2xl">
                {releaseMessage && (
                  <p
                    className={cn(
                      'alert mb-4',
                      releaseMessageError ? 'alert-error' : 'alert-success',
                    )}
                    role="status"
                  >
                    {releaseMessage}
                  </p>
                )}

                {currentRelease?.status === 'SCANNING' && (
                  <div className="mb-4 space-y-2">
                    <ProgressBar />
                    <p className="text-xs text-info">
                      {t('publisher.scanInProgress')}
                    </p>
                  </div>
                )}

                {pollTimedOut && !busy && (
                  <p
                    className="alert alert-info mb-4 flex flex-wrap items-center gap-2"
                    role="status"
                  >
                    {t('publisher.stillProcessing')}
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm tap-compact"
                      onClick={() => void refreshCurrentStatus()}
                    >
                      {t('publisher.refreshStatus')}
                    </button>
                  </p>
                )}

                {Boolean(currentRelease?.findings?.length) && (
                  <ul className="mb-4 space-y-1 text-sm">
                    {(currentRelease?.findings ?? []).map((finding) => (
                      <li key={finding.rule} className="card p-2">
                        <Badge
                          variant="outline"
                          className={cn(
                            badgeBaseClass,
                            finding.severity === 'ERROR' ||
                              finding.severity === 'CRITICAL'
                              ? badgeToneClass.danger
                              : badgeToneClass.muted,
                          )}
                        >
                          {finding.severity}
                        </Badge>
                        {` ${finding.rule} — ${finding.message}`}
                      </li>
                    ))}
                  </ul>
                )}

                <div className="flex flex-wrap items-center justify-end gap-3 border-t border-line pt-4">
                  <button
                    type="button"
                    className="btn btn-secondary"
                    onClick={() => setStep(2)}
                  >
                    ← {t('publisher.step2')}
                  </button>
                  <button
                    type="button"
                    className="btn btn-primary"
                    disabled={busy || !canUploadCurrent}
                    onClick={() => void uploadAndSubmit()}
                  >
                    {submitPhase === 'uploading'
                      ? t('publisher.uploading')
                      : submitPhase === 'submitting'
                        ? t('publisher.submitting')
                        : t('publisher.uploadAndSubmit')}
                  </button>
                </div>
              </div>


            </div>
          </section>
        </div>
              <aside className="card h-fit min-w-0 p-4 text-sm min-[1200px]:sticky min-[1200px]:top-24">
                <h3 className="mb-3 text-xs font-semibold uppercase tracking-wide text-muted">
                  {t('publisher.summaryTitle')}
                </h3>
                <dl className="space-y-2">
                  <div className="flex justify-between gap-3">
                    <dt className="text-muted">{t('publisher.name')}</dt>
                    <dd className="min-w-0 truncate font-medium">
                      {selectedListing?.name}
                    </dd>
                  </div>
                  <div className="flex justify-between gap-3">
                    <dt className="text-muted">{t('publisher.version')}</dt>
                    <dd className="font-medium">
                      {currentRelease ? `v${currentRelease.version}` : '—'}
                    </dd>
                  </div>
                  <div className="flex justify-between gap-3">
                    <dt className="text-muted">{t('publisher.channel')}</dt>
                    <dd className="font-medium">
                      {currentRelease
                        ? t(`channel.${currentRelease.channel}`)
                        : '—'}
                    </dd>
                  </div>
                  <div className="flex justify-between gap-3">
                    <dt className="text-muted">{t('publisher.file')}</dt>
                    <dd className="min-w-0 truncate font-medium">
                      {packageName
                        ? `${packageName} (${formatSize(packageSize)})`
                        : '—'}
                    </dd>
                  </div>
                  <div className="flex justify-between gap-3">
                    <dt className="text-muted">{t('publisher.status')}</dt>
                    <dd>
                      {currentRelease ? (
                        <StateChip status={currentRelease.status} />
                      ) : (
                        '—'
                      )}
                    </dd>
                  </div>
                </dl>
                {/* 删除版本是次级/危险操作,不与提交竞争。 */}
                {canDeleteCurrent && (
                  <button
                    type="button"
                    className="btn btn-danger-outline btn-sm tap-compact mt-4 w-full"
                    disabled={busy}
                    onClick={() => void deleteCurrentRelease()}
                  >
                    {t('publisher.deleteRelease')}
                  </button>
                )}
              </aside>
      </div>
    </div>
  );
}
