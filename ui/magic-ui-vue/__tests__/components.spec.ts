import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import {
  Badge,
  BlurFade,
  MagicCard,
  Marquee,
  NumberTicker,
  ProgressBar,
  ShimmerButton,
} from '../src/index';

describe('Badge', () => {
  it('renders slotted content with a tone class', () => {
    const wrapper = mount(Badge, { props: { tone: 'success' }, slots: { default: 'stable' } });
    expect(wrapper.text()).toContain('stable');
    expect(wrapper.classes()).toContain('magic-badge--success');
  });
});

describe('MagicCard', () => {
  it('renders content and optional border', () => {
    const wrapper = mount(MagicCard, { props: { bordered: true }, slots: { default: '<p>hi</p>' } });
    expect(wrapper.text()).toContain('hi');
    expect(wrapper.classes()).toContain('magic-card--bordered');
  });
});

describe('Marquee', () => {
  it('duplicates the track for a seamless loop', () => {
    const wrapper = mount(Marquee, {
      slots: { default: '<span>item</span>' },
      attachTo: document.body,
    });
    const groups = wrapper.findAll('.magic-marquee__group');
    expect(groups.length).toBe(2);
    const second = groups[1];
    expect(second.attributes('aria-hidden')).toBe('true');
    wrapper.unmount();
  });
});

describe('ShimmerButton', () => {
  it('renders a button with label above the sheen layer', () => {
    const wrapper = mount(ShimmerButton, { slots: { default: 'Install' } });
    expect(wrapper.element.tagName).toBe('BUTTON');
    expect(wrapper.find('.magic-shimmer-btn__label').text()).toBe('Install');
  });
});

describe('ProgressBar', () => {
  it('is determinate with a value', () => {
    const wrapper = mount(ProgressBar, { props: { value: 40 }, attachTo: document.body });
    expect(wrapper.attributes('aria-valuenow')).toBe('40');
    wrapper.unmount();
  });

  it('is indeterminate without a value', () => {
    const wrapper = mount(ProgressBar, { attachTo: document.body });
    expect(wrapper.attributes('aria-valuenow')).toBeUndefined();
    expect(wrapper.find('.magic-progress-indeterminate').exists()).toBe(true);
    wrapper.unmount();
  });
});

describe('NumberTicker', () => {
  it('renders the target value', async () => {
    const wrapper = mount(NumberTicker, { props: { value: 42, duration: 0.05 } });
    // Poll until the count-up settles (jsdom rAF timing varies).
    for (let i = 0; i < 50 && wrapper.text() !== '42'; i++) {
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    expect(wrapper.text()).toBe('42');
  });
});

describe('BlurFade', () => {
  it('becomes visible after mount', async () => {
    const wrapper = mount(BlurFade, { props: { delay: 0, duration: 0.05 } });
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(wrapper.find('.magic-blur-fade--visible').exists()).toBe(true);
  });
});

describe('reduced motion', () => {
  it('exports the useReducedMotion composable', async () => {
    const { useReducedMotion } = await import('../src/index');
    expect(typeof useReducedMotion).toBe('function');
  });
});
