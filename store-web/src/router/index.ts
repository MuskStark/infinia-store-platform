import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '../stores/auth';

/** Routes with code splitting and role-aware guards (design §12.2). */
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
      path: '/publisher',
      name: 'publisher',
      component: () => import('../views/PublisherView.vue'),
      meta: { requiresAuth: true, requiresRole: 'PUBLISHER' },
    },
    {
      path: '/review',
      name: 'review',
      component: () => import('../views/ReviewView.vue'),
      meta: { requiresAuth: true, requiresRole: 'REVIEWER' },
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
  if (to.meta.requiresRole && !auth.roles.includes(String(to.meta.requiresRole))) {
    return { name: 'discover' };
  }
  return true;
});

export default router;
