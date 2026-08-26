<script setup lang="ts">
import { onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { useLibraryStore } from '../stores/library';
import { Badge, BlurFade } from '@infinia/magic-ui-vue';
import EmptyState from '../components/EmptyState.vue';
import LoadingGrid from '../components/LoadingGrid.vue';

const { t } = useI18n();
const library = useLibraryStore();
onMounted(() => library.load());

/** infinia://plugin/official/markdown → /listing/official/markdown */
function favoriteRoute(favorite: { listingCoordinate?: string; name?: string }) {
  const coordinate = favorite.listingCoordinate ?? '';
  const parts = coordinate.replace('infinia://', '').split('/'); // type namespace slug
  return parts.length >= 3 ? `/listing/${parts[1]}/${parts[2]}` : '/browse';
}
</script>

<template>
  <div class="space-y-10">
    <h1 class="text-2xl font-bold">{{ t('library.title') }}</h1>

    <LoadingGrid v-if="library.loading" />
    <template v-else>
      <section aria-labelledby="favorites-heading">
        <h2 id="favorites-heading" class="mb-3 text-lg font-semibold">{{ t('library.favorites') }}</h2>
        <EmptyState v-if="!library.library?.favorites?.length" :title="t('library.noFavorites')" />
        <BlurFade v-else>
          <ul class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            <li
              v-for="favorite in library.library.favorites"
              :key="favorite.listingCoordinate ?? favorite.name"
              class="rounded-2xl border border-line p-4 dark:border-slate-800"
            >
              <RouterLink
                :to="favoriteRoute(favorite)"
                class="font-medium hover:text-accent"
              >
                {{ favorite.name ?? favorite.listingCoordinate }}
              </RouterLink>
              <div class="mt-2">
                <Badge tone="muted">{{ t(`type.${favorite.type}`) }}</Badge>
              </div>
            </li>
          </ul>
        </BlurFade>
      </section>

      <section aria-labelledby="entitlements-heading">
        <h2 id="entitlements-heading" class="mb-3 text-lg font-semibold">{{ t('library.entitlements') }}</h2>
        <EmptyState v-if="!library.library?.entitlements?.length" :title="t('library.noEntitlements')" />
        <ul v-else class="space-y-2 text-sm">
          <li
            v-for="entitlement in library.library.entitlements"
            :key="entitlement.listingCoordinate"
            class="flex items-center gap-2 rounded-xl border border-line p-3 dark:border-slate-800"
          >
            <code class="text-xs">{{ entitlement.listingCoordinate }}</code>
            <Badge v-if="entitlement.free" tone="success">{{ t('library.free') }}</Badge>
          </li>
        </ul>
      </section>

      <section aria-labelledby="history-heading">
        <h2 id="history-heading" class="mb-3 text-lg font-semibold">{{ t('library.installHistory') }}</h2>
        <EmptyState v-if="!library.library?.installHistory?.length" :title="t('library.noHistory')" />
        <table v-else class="w-full text-left text-sm">
          <thead>
            <tr class="text-muted">
              <th class="p-2">{{ t('listing.version') }}</th>
              <th class="p-2">{{ t('library.action') }}</th>
              <th class="p-2">{{ t('library.outcome') }}</th>
              <th class="p-2">{{ t('library.when') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="event in library.library.installHistory"
              :key="event.idempotencyKey"
              class="border-t border-line dark:border-slate-800"
            >
              <td class="p-2"><code class="text-xs">{{ event.coordinate }}</code></td>
              <td class="p-2">{{ event.action }}</td>
              <td class="p-2">{{ event.outcome }}</td>
              <td class="p-2 text-muted">{{ event.occurredAt }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>
  </div>
</template>
