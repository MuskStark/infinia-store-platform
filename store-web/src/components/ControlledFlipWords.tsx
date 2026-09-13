import { AnimatePresence, motion, useReducedMotion } from 'motion/react';

/** Aceternity Flip Words animation, controlled by the selected form instead of a timer.
 * https://ui.aceternity.com/components/flip-words
 */
export default function ControlledFlipWords({ word }: { word: string }) {
  const reduced = useReducedMotion();
  return <span className="relative inline-grid align-baseline" aria-hidden="true">
    <AnimatePresence initial={false} mode="popLayout">
      <motion.span key={word} className="inline-block whitespace-pre"
        initial={reduced ? false : { opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        exit={reduced ? { opacity: 0 } : { opacity: 0, y: -40, x: 40, filter: 'blur(8px)', scale: 2 }}
        transition={reduced ? { duration: 0 } : { type: 'spring', stiffness: 100, damping: 10 }}>
        {Array.from(word).map((letter, index) => <motion.span key={index} className="inline-block whitespace-pre"
          initial={reduced ? false : { opacity: 0, y: 10, filter: 'blur(8px)' }}
          animate={{ opacity: 1, y: 0, filter: 'blur(0px)' }}
          transition={{ delay: reduced ? 0 : index * 0.05, duration: reduced ? 0 : 0.2 }}>{letter}</motion.span>)}
      </motion.span>
    </AnimatePresence>
  </span>;
}
