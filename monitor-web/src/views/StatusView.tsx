import { useEffect, useMemo, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { useTranslation } from 'react-i18next';
import type { StatusIndicator, StatusDayUptime } from '../api/client';
import { useStatusFeed, type FeedConnection } from '../hooks/useStatusFeed';
import { daySlots, HEX_WIDTH, HEX_HEIGHT, DAY_WIDTH, DAY_HEIGHT } from '../hive';
import { formatDateTime } from '../utils/format';
import StatusTabs from '../components/StatusTabs';

const indicators: StatusIndicator[] = ['operational','degraded','partial_outage','major_outage','no_data'];
const names = { operational:'operational', degraded:'degraded', partial_outage:'partialOutage', major_outage:'majorOutage', no_data:'noData' };
/** Beyond this, data from a dropped connection stops counting as current. */
const LOCAL_STALE_MS = 180_000;
function hex(x: number, y: number, w: number) {
  const h = w * 2 / Math.sqrt(3);
  return [[0,-h/2],[w/2,-h/4],[w/2,h/4],[0,h/2],[-w/2,h/4],[-w/2,-h/4]].map(([dx,dy]) => `${x+dx},${y+dy}`).join(' ');
}
function positions(count: number) {
  const cells = [{q:0,r:0}];
  for (let ring = 1; cells.length < count; ring++) {
    const next = [];
    for (let q=-ring;q<=ring;q++) for(let r=-ring;r<=ring;r++)
      if ((Math.abs(q)+Math.abs(r)+Math.abs(q+r))/2===ring) next.push({q,r});
    next.sort((a,b)=>Math.atan2(a.q+a.r/2,-a.r)-Math.atan2(b.q+b.r/2,-b.r));
    cells.push(...next);
  }
  return cells;
}
const connectionClass: Record<FeedConnection, string> = {
  live: 'operational',
  connecting: 'no_data',
  reconnecting: 'degraded',
  disconnected: 'major_outage',
  unsupported: 'no_data',
};
export default function StatusView() {
  const { t, i18n } = useTranslation();
  const reduced = useReducedMotion();
  const feed = useStatusFeed();
  const { status, incidents, error, feedError, loading, updated, connection, refresh } = feed;
  const [now,setNow] = useState(Date.now());
  const [selected,setSelected] = useState<string|null>(null);
  const [tab,setTab] = useState('hive');
  const [detail,setDetail] = useState<string|null>(null);
  useEffect(() => {
    const tick=setInterval(()=>setNow(Date.now()),1000);
    return ()=>clearInterval(tick);
  },[]);
  const indicatorText = (value: StatusIndicator) => t('status.indicator.'+names[value]);
  const componentText = (key:string) => t('status.component.'+key,{defaultValue:key});
  const components=status?.components ?? [];
  const cells=useMemo(()=>positions(Math.max(19,components.length+1)),[components.length]);
  const points=cells.map(({q,r})=>({x:(q+r/2)*210,y:r*182}));
  const minX=Math.min(...points.map(p=>p.x))-110,minY=Math.min(...points.map(p=>p.y))-125;
  const width=Math.max(...points.map(p=>p.x))-minX+110,height=Math.max(...points.map(p=>p.y))-minY+125;
  const measured=components.filter(c=>c.uptime90d!=null);
  const average=measured.length ? measured.reduce((sum,c)=>sum+c.uptime90d!,0)/measured.length : null;
  const choose = (key:string)=>{setSelected(current=>current===key?null:key);setDetail(null);};
  const overall=status?.indicator ?? 'no_data';
  // A dropped stream keeps the last data but must not pass it off as current.
  const localStale = connection!=='live' && updated!=null && now-updated>LOCAL_STALE_MS;
  const dayLabel = (componentKey:string, day:StatusDayUptime) => {
    const parts=[componentText(componentKey), day.date, indicatorText(day.indicator)];
    if (day.uptimePercent!=null) parts.push(t('status.dayUptime',{percent:day.uptimePercent.toFixed(2)}));
    if (day.coveragePercent!=null) parts.push(t('status.dayCoverage',{percent:day.coveragePercent.toFixed(1)}));
    if (day.sampled) parts.push(t('status.daySampled'));
    return parts.join(' · ');
  };
  return <div className="status-page">
    <div className="page-heading"><div><span className="eyebrow">INFINIA · STATUS</span><h1>{t('status.title')}</h1></div>
      <div className="refresh-actions"><span className={'connection '+connection} data-testid="connection-state"><i className={connectionClass[connection]}/>{t('status.connection.'+connection)}</span>
        {updated && <span className="refresh-time">{now-updated<5000?t('status.updatedJustNow'):t('status.updatedSecondsAgo',{n:Math.max(0,Math.floor((now-updated)/1000))})}</span>}<button className="btn btn-secondary" disabled={loading} onClick={refresh}>{t('status.refresh')}</button></div></div>
    {error && <div role="alert" className="notice">{error} · {t('common.error')} <button className="btn btn-secondary" onClick={refresh}>{t('common.retry')}</button></div>}
    {status?.stale && <div role="alert" className="notice" data-testid="stale-banner">{status.mirroredAt ? t('status.staleBanner',{time:formatDateTime(status.mirroredAt)}) : t('status.neverReached')}</div>}
    {localStale && !status?.stale && <div role="alert" className="notice" data-testid="local-stale-banner">{t('status.localStaleBanner',{time:formatDateTime(new Date(updated!).toISOString())})}</div>}
    {!status && loading ? <div className="loading" role="status">…</div> : status && <>
      <StatusTabs value={tab} onChange={setTab} labels={[t('status.components'),t('status.pastIncidents')]} />
      <AnimatePresence mode="wait">
        <motion.section key={tab} role="tabpanel" id={tab+'-panel'} aria-labelledby={tab+'-tab'} initial={{opacity:0,y:reduced?0:8}} animate={{opacity:1,y:0}} exit={{opacity:0}} transition={{duration:reduced?0:.18}}>
        {tab==='hive'? <div className="card hive-panel">
          <div className="panel-heading"><h2>{t('status.components')} <span className="count">{components.length}</span></h2><span>{t('status.historyNote')}</span></div>
          <div className="hive-layout">
            <div className="hive-visual">
              <svg className="hive" viewBox={`${minX} ${minY} ${width} ${height}`} aria-label={t('status.hiveHint')}>
                {cells.map((_,index)=>{
                  const {x,y}=points[index],component=components[index-1],isCenter=index===0;
                  const pending=!isCenter && component?.pending===true;
                  return <g key={component?.key ?? index} className={(selected && component?.key!==selected && !isCenter?'comb dimmed':'comb')+(pending?' pending':'')}>
                    <polygon points={hex(x,y,204)} className={selected===component?.key?'comb-outline selected':'comb-outline'} />
                    {isCenter ? <g className="hive-overall"><text x={x} y={y-20} textAnchor="middle" className="center-title">{t('status.overall')}</text><text x={x} y={y+12} textAnchor="middle" className={'center-status '+overall}>{indicatorText(overall)}</text><text x={x} y={y+42} textAnchor="middle" className="center-metric">{average==null?'—':average.toFixed(2)+'%'}</text></g> : component &&
                      <g>{component.history.slice(-90).map((day,i)=>{
                        const pos=daySlots[i]; if(!pos)return null;
                        const label=dayLabel(component.key, day);
                        return <polygon key={day.date} className={'hive-day '+day.indicator}
                          points={hex(x-HEX_WIDTH/2+pos.x,y-HEX_HEIGHT/2+pos.y,DAY_WIDTH*.84)}
                          role="button" tabIndex={selected===component.key?0:-1} aria-label={label}
                          onMouseEnter={()=>setDetail(label)} onFocus={()=>setDetail(label)} onClick={()=>setDetail(label)}
                          onKeyDown={e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();setDetail(label);}}}><title>{label}</title></polygon>;
                      })}</g>}
                  </g>;
                })}
              </svg>
              <p className="hive-detail" role="status">{detail ?? t('status.hiveHint')}</p>
              <div className="state-legend">{indicators.map(value=><span key={value}><i className={value}/>{indicatorText(value)}</span>)}</div>
            </div>
            <div className="service-list" data-testid="hive-legend">{components.map((c,index)=><button key={c.key} className={'service-row '+(selected===c.key?'active':'')} aria-pressed={selected===c.key} onClick={()=>choose(c.key)}>
              <span className="service-number">{(index+1).toString().padStart(2,'0')}</span><span className="service-name">{componentText(c.key)}<small data-testid="component-live-status"><i className={c.indicator}/>{indicatorText(c.indicator)}{c.pending && <em className="pending-flag">{t('status.pending')}</em>}</small></span>
              <span className="uptime">{c.uptime90d==null?'—':c.uptime90d.toFixed(2)+'%'}</span>
            </button>)}</div>
          </div>
        </div> : <div className="card incidents" data-testid="status-incidents"><h2>{t('status.pastIncidents')}</h2>
          {feedError ? <p role="alert">{t('common.error')}</p> : !incidents.length ? <p className="empty">{t('status.noIncidents')}</p> : [...incidents].sort((a,b)=>b.startedAt.localeCompare(a.startedAt)).map(incident=><article key={incident.incidentId}>
            <span className={'incident-state '+(incident.status==='resolved'?'operational':'major_outage')}>{t(incident.status==='resolved'?'status.incidentResolved':'status.incidentInvestigating')}</span>
            <h3>{i18n.exists('status.component.'+incident.component)?componentText(incident.component)+t(incident.impact==='outage'?'status.incidentUnavailable':'status.incidentDegraded'):incident.title}</h3>
            <p>{formatDateTime(incident.startedAt)}{incident.resolvedAt && ' — '+formatDateTime(incident.resolvedAt)}</p>
          </article>)}</div>}
        </motion.section>
      </AnimatePresence>
      <p className="footnote">{t('status.samplingHint')}<br />{t('status.autoRefresh')}</p>
    </>}
  </div>;
}
