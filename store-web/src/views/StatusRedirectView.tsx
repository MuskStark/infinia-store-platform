import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { redirectToMonitor, resolveMonitorUrl } from '../status/monitorHandoff';

/**
 * The status page now lives on the standalone monitor (ADR-011) — the page
 * that must stay reachable when this app is not. /status keeps working as a
 * deep link by handing the browser to the monitor's public address.
 */
export default function StatusRedirectView() {
  const { t } = useTranslation();
  const [unconfigured, setUnconfigured] = useState(false);

  useEffect(() => {
    void (async () => {
      const target = await resolveMonitorUrl();
      if (target !== null) {
        redirectToMonitor(target);
      } else {
        // Degrade honestly instead of dumping raw /api/v1/status JSON on users.
        setUnconfigured(true);
      }
    })();
  }, []);

  if (unconfigured) {
    return (
      <div
        className="mx-auto grid max-w-2xl place-items-center gap-4 py-24 text-center"
        data-testid="status-unconfigured"
      >
        <p className="text-lg font-semibold">
          {t('statusRedirect.unconfiguredTitle')}
        </p>
        <p className="text-sm text-muted">
          {t('statusRedirect.unconfiguredBody')}
        </p>
        <Link className="btn btn-primary px-5" to="/">
          {t('common.backHome')}
        </Link>
      </div>
    );
  }
  return (
    <div className="mx-auto max-w-5xl py-16 text-center text-sm text-muted">
      {t('statusRedirect.redirecting')}
    </div>
  );
}
