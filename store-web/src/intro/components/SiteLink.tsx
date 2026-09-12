import type { ComponentProps } from 'react';
import { Link } from 'react-router';

/** Same-origin routes keep the shared session; anchors and external links remain native. */
export default function SiteLink({ href = '', ...props }: ComponentProps<'a'>) {
  return href.startsWith('/') && !href.startsWith('//')
    ? <Link to={href} {...props} />
    : <a href={href} {...props} />;
}
