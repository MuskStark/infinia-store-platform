import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import HexWash from '../components/HexWash';

export default function NotFoundView() {
  const { t } = useTranslation();
  return (
    <div className="relative grid min-h-[60vh] place-items-center overflow-hidden rounded-lg text-center">
      <HexWash fade="to bottom" />
      <div className="relative grid place-items-center gap-4">
        {/* Quiet static 404: neutral text with one gold accent (§6.15). */}
        <p className="text-7xl font-extrabold tracking-tight text-accent">404</p>
        <p className="text-lg">{t('common.notFound')}</p>
        <div className="flex flex-wrap justify-center gap-2">
          <Link className="btn btn-primary px-5" to="/store">
            {t('common.backHome')}
          </Link>
          <Link className="btn btn-secondary px-5" to="/store/browse">
            {t('nav.browse')}
          </Link>
        </div>
      </div>
    </div>
  );
}
