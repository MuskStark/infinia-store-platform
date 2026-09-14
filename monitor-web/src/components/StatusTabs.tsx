import { useId } from 'react';
import { motion, useReducedMotion } from 'motion/react';
// Adapted from Aceternity Tabs: https://ui.aceternity.com/components/tabs
// Shared-layout pill retained; card stacking omitted so status data stays legible.
export default function StatusTabs({ value, onChange, labels }: {value: string; onChange: (v: string) => void; labels: string[]}) {
  const id = useId(); const reduced = useReducedMotion();
  return <div className="tabs" role="tablist">{['hive','incidents'].map((key,index) =>
    <button key={key} role="tab" id={key + '-tab'} aria-controls={key + '-panel'} aria-selected={value === key}
      tabIndex={value === key ? 0 : -1} onClick={() => onChange(key)}
      onKeyDown={e => { if (['ArrowLeft','ArrowRight','Home','End'].includes(e.key)) { e.preventDefault(); const next = e.key === 'Home' ? 'hive' : e.key === 'End' ? 'incidents' : key === 'hive' ? 'incidents' : 'hive'; onChange(next); document.getElementById(next + '-tab')?.focus(); } }}>
      {value === key && <motion.span className="tab-pill" layoutId={id} transition={reduced ? {duration:0} : {type:'spring',bounce:.2,duration:.4}} />}
      <span className="tab-label">{labels[index]}</span>
    </button>)}</div>;
}
