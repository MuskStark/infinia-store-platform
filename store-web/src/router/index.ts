import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '../stores/auth';

/** Routes with code splitting and role-aware guards (design §12.2). */
declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean;
    requiresRole?: string[];
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'discover',
      component: () => import('../views/DiscoverView.vue'),
    },
    {
      path: '/browse',
      name: 'browse',
      component: () => import('../views/BrowseView.vue'),
    },
    {
      path: '/listing/:namespace/:slug',
      name: 'listing',
      component: () => import('../views/ListingDetailView.vue'),
      props: true,
    },
    {
      path: '/library',
      name: 'library',
      component: () => import('../views/LibraryView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/account',
      name: 'account',
      component: () => import('../views/AccountView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/organizations',
      name: 'organizations',
      component: () => import('../views/OrganizationsView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/admin',
      name: 'admin',
      component: () => import('../views/AdminView.vue'),
      meta: { requiresAuth: true, requiresRole: ['PLATFORM_ADMIN'] },
    },
    {
      path: '/publisher',
      name: 'publisher',
      component: () => import('../views/PublisherView.vue'),
      meta: { requiresAuth: true, requiresRole: ['PUBLISHER', 'ORG_ADMIN', 'REVIEWER', 'PLATFORM_ADMIN'] },
    },
    {
      path: '/review',
      name: 'review',
      component: () => import('../views/ReviewView.vue'),
      meta: { requiresAuth: true, requiresRole: ['REVIEWER', 'PLATFORM_ADMIN'] },
    },
    {
      path: '/status',
      name: 'status',
      component: () => import('../views/StatusView.vue'),
    },
    {
      path: '/signin',
      name: 'signin',
      component: () => import('../views/SignInView.vue'),
    },
    {
      path: '/callback',
      name: 'callback',
      component: () => import('../views/CallbackView.vue'),
    },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue') },
  ],
});

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  if (!auth.ready) {
    await auth.load();
  }
  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { name: 'signin', query: { redirect: to.fullPath } };
  }
  const required = Array.isArray(to.meta.requiresRole) ? to.meta.requiresRole : [];
  if (required.length > 0 && !required.some((role) => auth.roles.includes(role))) {
    return { name: 'discover' };
  }
  return true;
});

export default router;
