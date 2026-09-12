import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import HexWash from '../components/HexWash';
import { AuroraText } from '../components/magicui/aurora-text';
import MagicCard from '../components/MagicCard';
import { api, type CatalogItem, type CatalogPage } from '../api/client';
import { BlurFade } from '../components/magicui/blur-fade';
import { NumberTicker } from '../components/magicui/number-ticker';
import ListingCard from '../components/ListingCard';
import LoadingGrid from '../components/LoadingGrid';
import ErrorState from '../components/ErrorState';
import EmptyState from '../components/EmptyState';

export default function DiscoverView() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [items, setItems] = useState<CatalogItem[]>([]);
  const [totalListings, setTotalListings] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const page = await api.get<CatalogPage>(
        '/api/v1/catalog?limit=24&sort=downloads',
      );
      setItems(page.items ?? []);
      setTotalListings(page.totalEstimate ?? page.items?.length ?? 0);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'error');
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    void load();
  }, []);

  function submitSearch(event: React.FormEvent) {
    event.preventDefault();
    navigate({
      pathname: '/store/browse',
      search: searchQuery ? `?q=${encodeURIComponent(searchQuery)}` : '',
    });
  }

  // Editorial shelf from platform admins (design §12.4); falls back to the most
  // downloaded listings until the admin features something.
  const featured = useMemo(
    () =>
      items.some((i: { featured?: boolean }) => i.featured)
        ? items.filter((i: { featured?: boolean }) => i.featured).slice(0, 5)
        : items.slice(0, 5),
    [items],
  );
  const latest = useMemo(() => items.slice(5), [items]);
  const totalDownloads = useMemo(
    () => items.reduce((sum, item) => sum + (item.downloads ?? 0), 0),
    [items],
  );
  const types = ['APP', 'PLUGIN', 'SKILL', 'MCP', 'FLOW'] as const;

  return (
    <div>
      {/* Marketplace hero: the living hive wash fading down, aurora title,
     composite search below. Full-bleed band on every screen width. */}
      <section className="relative overflow-hidden border-b border-line bg-surface-muted px-4 pb-14 pt-16">
        <HexWash fade="to bottom" />

        <div className="relative mx-auto max-w-7xl">
          <div className="max-w-2xl">
            <BlurFade>
              <p className="mb-3 flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.25em] text-muted">
                <span
                  className="h-2 w-2 rounded-full"
                  style={{ background: 'var(--hero-gradient)' }}
                  aria-hidden="true"
                />
                Infinia Store
              </p>
              <h1 className="text-4xl font-bold leading-tight tracking-tight text-ink md:text-[2.75rem] md:leading-[1.15]">
                <AuroraText
                  colors={['#fc801d', '#fe2857', '#a73afd', '#0b70f5']}
                >
                  {t('discover.heroTitle')}
                </AuroraText>
              </h1>
              <p className="mt-4 text-lg leading-7 text-muted">
                {t('discover.heroSubtitle')}
              </p>
            </BlurFade>

            {/* Composite search bar: blue submit segment + input, marketplace style. */}
            <BlurFade delay={0.1}>
              <form
                className="mt-8 flex max-w-2xl"
                role="search"
                onSubmit={submitSearch}
              >
                <label className="sr-only" htmlFor="hero-search">
                  {t('common.search')}
                </label>
                <button
                  type="submit"
                  className="shrink-0 rounded-l-lg bg-brand px-5 text-sm font-semibold text-[#18181b] transition-colors hover:brightness-105"
                >
                  {t('common.searchAction')}
                </button>
                <input
                  id="hero-search"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  type="search"
                  placeholder={t('common.search')}
                  className="h-11 w-full rounded-r-lg border border-line border-l-0 bg-surface px-4 text-[15px] text-ink placeholder:text-muted/70 focus:border-accent focus:outline-none"
                />
              </form>
            </BlurFade>

            <BlurFade delay={0.2}>
              <div className="mt-6 flex gap-8">
                <div>
                  <div className="text-2xl font-bold text-ink">
                    <NumberTicker
                      value={totalListings}
                      className="text-2xl font-bold text-ink"
                    />
                  </div>
                  <div className="mt-0.5 text-xs text-muted">
                    {t('discover.statsListings')}
                  </div>
                </div>
                <div>
                  <div className="text-2xl font-bold text-ink">
                    <NumberTicker
                      value={totalDownloads}
                      className="text-2xl font-bold text-ink"
                    />
                  </div>
                  <div className="mt-0.5 text-xs text-muted">
                    {t('discover.statsDownloads')}
                  </div>
                </div>
              </div>
            </BlurFade>
          </div>
        </div>
      </section>

      <div className="page-shell space-y-12">
        <section aria-labelledby="types-heading">
          <h2 id="types-heading" className="mb-4 text-lg font-bold">
            {t('discover.categories')}
          </h2>
          <div className="flex flex-wrap gap-2.5">
            {types.map((type) => (
              <Link
                key={type}
                to={`/store/browse?type=${type}`}
                className="rounded-lg"
              >
                <MagicCard className="rounded-lg px-5 py-3 text-sm font-medium">
                  {t(`type.${type}`)}
                </MagicCard>
              </Link>
            ))}
          </div>
        </section>

        <section aria-labelledby="featured-heading">
          <div className="mb-4 flex items-center justify-between">
            <h2 id="featured-heading" className="text-lg font-bold">
              {t('discover.featured')}
            </h2>
            <Link className="text-sm text-accent hover:underline" to="/store/browse">
              {t('common.viewAll')}
            </Link>
          </div>
          {error ? (
            <ErrorState message={error} onRetry={() => void load()} />
          ) : loading ? (
            <LoadingGrid />
          ) : !featured.length ? (
            <EmptyState title={t('discover.noFeatured')} />
          ) : (
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {featured.map((item, index) => (
                <BlurFade
                  key={item.coordinate}
                  delay={Math.min(index * 0.06, 0.3)}
                >
                  <ListingCard item={item} featured />
                </BlurFade>
              ))}
            </div>
          )}
        </section>

        <section aria-labelledby="latest-heading">
          <h2 id="latest-heading" className="mb-4 text-lg font-bold">
            {t('discover.latest')}
          </h2>
          {error ? <ErrorState message={error} onRetry={() => void load()} /> : loading ? <LoadingGrid /> : !latest.length ? (
            <EmptyState title={t('discover.noLatest')} />
          ) : <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {latest.map((item, index) => (
              <BlurFade
                key={item.coordinate}
                delay={Math.min(index * 0.05, 0.35)}
              >
                <ListingCard item={item} />
              </BlurFade>
            ))}
          </div>}
        </section>
      </div>
    </div>
  );
}
