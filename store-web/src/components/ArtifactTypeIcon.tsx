/** Shared category symbols inside a subtle honeycomb outline. */
export default function ArtifactTypeIcon({ type, className = 'size-10' }: { type: string; className?: string }) {
  const paths: Record<string, string> = {
    APP: 'M9 10h14v10H9z M13 23h6 M16 20v3',
    PLUGIN: 'M13 9v5m6-5v5 M11 14h10v3a5 5 0 0 1-10 0z M16 22v2',
    SKILL: 'M10 10h5l1 2 1-2h5v12h-5l-1 1-1-1h-5z M16 12v11',
    MCP: 'M10 12h12v4H10z M10 19h12v4H10z M13 14h.01 M13 21h.01',
    FLOW: 'M10 10h5v5h-5z M19 19h5v5h-5z M15 12h6v7 M12 15v6h7',
  };
  return <svg viewBox="0 0 32 32" fill="none" className={className} aria-hidden="true">
    <path d="M16 1.5 29 9v14L16 30.5 3 23V9Z" fill="currentColor" fillOpacity=".07" stroke="currentColor" strokeOpacity=".22" />
    <path d={paths[type] ?? paths.PLUGIN} stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
  </svg>;
}
