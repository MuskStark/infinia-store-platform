import { useTranslation } from 'react-i18next';

export default function ErrorState({
  message,
  onRetry,
}: {
  message?: string;
  onRetry: () => void;
}) {
  const { t } = useTranslation();

  return (
    <div role="alert" className="alert alert-error text-center">
      <p className="font-medium">{message ?? t('common.error')}</p>
      <button className="btn btn-secondary mx-auto mt-3" onClick={onRetry}>
        {t('common.retry')}
      </button>
    </div>
  );
}
