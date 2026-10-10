/**
 * Hero — the top of the landing page.
 *
 * Two columns on a wide screen: the pitch and the entry points on the left, the live pipeline on
 * the right. Stacked on anything narrower than a tablet, with the pipeline first, because the
 * animation is the thing worth seeing on a phone and a wall of buttons is not.
 */

import React, {useCallback, useState} from 'react';
import Link from '@docusaurus/Link';
import VisionPipeline from '../VisionPipeline';
import MorphText from '../MorphText';
import styles from './styles.module.css';

/** The rotating object of the headline: things the library can actually find in a frame. Every one
 *  of these maps to a documented page, which is the point — it is a promise, not a mood board. */
const TARGETS = ['edges', 'contours', 'faces', 'motion', 'markers', 'gestures', 'depth'];

const INSTALL = 'mvn"com.worxbend::scalacv:0.4.1"';

function CopyButton({text}: {text: string}): React.ReactElement {
  const [copied, setCopied] = useState(false);

  const copy = useCallback(() => {
    // `navigator.clipboard` is absent on insecure origins; falling back to a hidden textarea keeps
    // the button honest on a plain-HTTP preview rather than silently doing nothing.
    const done = () => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1600);
    };
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(() => undefined);
      return;
    }
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand('copy');
      done();
    } finally {
      document.body.removeChild(ta);
    }
  }, [text]);

  return (
    <button type="button" className={styles.copy} onClick={copy} aria-live="polite">
      {copied ? 'copied' : 'copy'}
    </button>
  );
}

export default function Hero(): React.ReactElement {
  return (
    <header className={styles.hero}>
      {/* Backdrop layers, all decorative. Kept as siblings rather than pseudo-elements so each can
          have its own blend mode and animation without fighting for ::before/::after. */}
      <div className={styles.grid} aria-hidden="true" />
      <div className={styles.aurora} aria-hidden="true" />
      <div className={styles.sweep} aria-hidden="true" />

      <div className={styles.inner}>
        <div className={styles.pitch}>
          <p className={styles.eyebrow}>
            <span className={styles.chip}>OpenCV 4.14</span>
            <span className={styles.chip}>Scala 3.3 LTS</span>
            <span className={styles.chip}>JDK 17+</span>
            <span className={styles.chip}>headless</span>
          </p>

          <h1 className={styles.title}>
            Teach the JVM
            <br />
            to see <MorphText words={TARGETS} />
          </h1>

          <p className={styles.lede}>
            A fluent, typed <code>Image</code> pipeline over the complete OpenCV Java bindings.
            No raw <code>int</code> constants, no GUI toolkit, no <code>apt-get</code> — and every
            intermediate frees its own native memory before the next call sees it.
          </p>

          <div className={styles.ctas}>
            <Link className={styles.primary} to="/getting-started">
              Start in five minutes
              <span className={styles.arrow} aria-hidden="true">
                →
              </span>
            </Link>
            <Link className={styles.secondary} to="/tutorial">
              Build something first
            </Link>
          </div>

          <div className={styles.install}>
            <span className={styles.installLabel}>add it</span>
            <code className={styles.installCode}>{INSTALL}</code>
            <CopyButton text={INSTALL} />
          </div>
          <p className={styles.installNote}>
            Source tag 0.4.1; Central upload is disabled. Local install plus two native artifacts —{' '}
            <Link to="/getting-started">the install page explains why</Link>.
          </p>
        </div>

        <div className={styles.stage}>
          <VisionPipeline />
        </div>
      </div>
    </header>
  );
}
