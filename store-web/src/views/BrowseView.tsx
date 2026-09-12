import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { BlurFade } from '../components/magicui/blur-fade';
import HexWash from '../components/HexWash';
import {
  useCatalogStore,
  type ListingTypeFilter,
  type SortKey,
} from '../stores/catalog';
import ListingCard from '../components/ListingCard';
import LoadingGrid from '../components/LoadingGrid';
import ErrorState from '../components/ErrorState';
import EmptyState from '../components/EmptyState';
import SelectMenu from '../components/SelectMenu';
import { cn } from '@/lib/utils';

const TYPES: ListingTypeFilter[] = [
  null,
  'APP',
  'PLUGIN',
  'SKILL',
  'MCP',
  'FLOW',
];
const SORTS: SortKey[] = ['relevance', 'recent', 'downloads', 'favorites'];

export default function BrowseView() {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const catalog = useCatalogStore();

  // The URL's ?type/?q seed the initial browse (deep links from Discover).
  useEffect(() => {
    const type = (searchParams.get('type') as ListingTypeFilter) ?? null;
    catalog.set({
      type: type && TYPES.includes(type) ? type : null,
      query: searchParams.get('q') ?? '',
    });
    void catalog.browse();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Re-browse when the filters change — but not on mount (the seed effect just
  // issued the first request).
  const mounted = useRef(false);
  useEffect(() => {
    if (!mounted.current) {
      mounted.current = true;
      return;
    }
    void catalog.browse();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [catalog.type, catalog.sort]);

  function submitSearch(event?: React.FormEvent) {
    event?.preventDefault();
    if (catalog.query) {
      setSearchParams({ q: catalog.query });
    } else {
      setSearchParams({});
    }
    void catalog.browse();
  }

  return (
    <div className="page-shell relative space-y-6">
      <HexWash fade="to bottom" className="h-28 left-auto right-0 top-0 bottom-auto w-64 opacity-40" />
      {/* Composite search bar + filter row, marketplace search page style. */}
      <form className="relative flex" role="search" onSubmit={submitSearch}>
        <label className="sr-only" htmlFor="browse-search">
          {t('common.search')}
        </label>
        <input
          id="browse-search"
          value={catalog.query}
          onChange={(e) => catalog.set({ query: e.target.value })}
          type="search"
          placeholder={t('common.search')}
          className="h-11 w-full max-w-2xl rounded-l-lg border border-line bg-surface px-4 text-[15px] text-ink placeholder:text-muted/70 focus:border-accent focus:outline-none"
        />
        <button
          type="submit"
          className="shrink-0 rounded-r-lg bg-brand px-5 text-sm font-semibold text-[#18181b] transition-colors hover:brightness-105"
        >
          {t('common.searchAction')}
        </button>
      </form>

      <div className="relative flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap gap-2" role="tablist" aria-label="type">
          {TYPES.map((type) => (
            <button
              key={type ?? 'all'}
              className={cn(
                'rounded-lg border px-3.5 py-1.5 text-sm font-medium transition-colors',
                catalog.type === type
                  ? 'border-accent bg-accent/5 font-semibold text-accent'
                  : 'border-line bg-surface text-muted hover:border-muted/40 hover:text-ink',
              )}
              role="tab"
              aria-selected={catalog.type === type}
              onClick={() => catalog.set({ type })}
            >
              {type ? t(`type.${type}`) : t('common.viewAll')}
            </button>
          ))}
        </div>
        <SelectMenu
          value={catalog.sort}
          className="w-44"
          options={SORTS.map((sort) => ({
            value: sort,
            label: t(`sort.${sort}`),
          }))}
          triggerLabel={`${t('common.sort')}: ${t(`sort.${catalog.sort}`)}`}
          ariaLabel={t('common.sort')}
          onValueChange={(v) => catalog.set({ sort: v as SortKey })}
        />
      </div>

      <div className="relative">
        {catalog.error ? (
          <ErrorState
            message={catalog.error}
            onRetry={() => void catalog.browse()}
          />
        ) : catalog.loading && !catalog.items.length ? (
          <LoadingGrid />
        ) : !catalog.items.length ? (
          <EmptyState title={t('common.empty')} />
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {catalog.items.map((item, index) => (
              <BlurFade
                key={item.coordinate}
                delay={Math.min(index * 0.05, 0.4)}
              >
                <ListingCard item={item} />
              </BlurFade>
            ))}
          </div>
        )}
      </div>

      {catalog.nextCursor && (
        <div className="relative text-center">
          <button
            className="btn btn-secondary px-8"
            onClick={() => void catalog.browse(false)}
          >
            {t('common.viewAll')}
          </button>
        </div>
      )}
    </div>
  );
}
