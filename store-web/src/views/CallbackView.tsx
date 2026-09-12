import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { completeLogin, useAuth } from '../stores/auth';
import ProgressBar from '../components/ProgressBar';

export default function CallbackView() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const auth = useAuth();
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const code = searchParams.get('code') ?? undefined;
    const state = searchParams.get('state') ?? undefined;
    void (async () => {
      if (code && state) {
        const ok = await completeLogin(code, state);
        if (ok) {
          await auth.load();
          const redirect =
            sessionStorage.getItem('infinia.store.redirect') ?? '/store';
          sessionStorage.removeItem('infinia.store.redirect');
          navigate(redirect, { replace: true });
          return;
        }
      }
      setFailed(true);
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="mx-auto max-w-md space-y-4 py-20 text-center">
      {!failed ? (
        <>
          <p>{t('auth.callbackWorking')}</p>
          <ProgressBar />
        </>
      ) : (
        <>
          <p className="font-medium text-danger" role="alert">
            {t('auth.callbackFailed')}
          </p>
          <div className="flex justify-center gap-2">
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => navigate('/store/signin')}
            >
              {t('nav.signIn')}
            </button>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => navigate('/store')}
            >
              {t('common.backHome')}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
