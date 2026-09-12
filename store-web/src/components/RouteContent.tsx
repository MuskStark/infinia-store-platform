import { Component, Suspense, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router';
import ErrorState from './ErrorState';

/**
 * Route shell: renders the active view behind a lazy-chunk Suspense fallback
 * and an error boundary that offers a full reload (a reload also replaces an
 * outdated entry bundle after a deployment — matching the old router.onError +
 * onErrorCaptured behaviour). Keyed by pathname so a successful navigation
 * clears a previous failure instead of wedging the router.
 */

function RouteFallback() {
  const { t } = useTranslation();
  return (
    <p role="status" className="py-8 text-center text-muted">
      {t('common.loading')}
    </p>
  );
}

function RouteFailure() {
  const { t } = useTranslation();
  const location = useLocation();
  // A full reload also replaces an outdated entry bundle after a deployment.
  const retry = () =>
    window.location.assign(location.pathname + location.search + location.hash);
  return <ErrorState message={t('common.pageLoadError')} onRetry={retry} />;
}

class RouteErrorBoundary extends Component<
  { children: ReactNode },
  { failed: boolean }
> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    if (this.state.failed) {
      return <RouteFailure />;
    }
    return this.props.children;
  }
}

export default function RouteContent({ children }: { children: ReactNode }) {
  const location = useLocation();
  return (
    <RouteErrorBoundary key={location.pathname}>
      <Suspense fallback={<RouteFallback />}>{children}</Suspense>
    </RouteErrorBoundary>
  );
}
