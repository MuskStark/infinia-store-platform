import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { i18n } from '../src/i18n';
window.matchMedia = ((query: string) => ({ matches:false,media:query,addListener(){},removeListener(){},addEventListener(){},removeEventListener(){},dispatchEvent(){return false;} })) as typeof window.matchMedia;
await i18n.changeLanguage('en');
afterEach(cleanup);
