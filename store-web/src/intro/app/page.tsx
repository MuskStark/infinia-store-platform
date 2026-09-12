import { useEffect } from "react";
import { useLocation } from "react-router";
import "../intro.css";
import { Navbar } from "@/intro/components/site/navbar";
import { Hero } from "@/intro/components/site/hero";
import { StatsStrip } from "@/intro/components/site/stats";
import { Features } from "@/intro/components/site/features";
import { Surfaces } from "@/intro/components/site/surfaces";
import { HowItWorks } from "@/intro/components/site/how-it-works";
import { PluginsMarquee } from "@/intro/components/site/plugins-marquee";
import { Security } from "@/intro/components/site/security";
import { Downloads } from "@/intro/components/site/downloads";
import { Faq } from "@/intro/components/site/faq";
import { CallToAction } from "@/intro/components/site/cta";
import { Footer } from "@/intro/components/site/footer";

export default function IntroductionView() {
  const { hash } = useLocation();
  useEffect(() => {
    document.documentElement.classList.add('introduction-scrollbar');
    const previous = document.title;
    document.title = 'Infinia — the AI-native orchestration platform';
    const entries = [
      ['name', 'description', 'Infinia (蜂语 / FengYu) turns natural-language goals into multi-step business workflows with plugins, skills and AI tools.'],
      ['property', 'og:title', 'Infinia — the AI-native orchestration platform'],
      ['property', 'og:description', 'State a goal; Infinia orchestrates plugins, skills, flows and AI tools locally, with your approval.'],
      ['property', 'og:type', 'website'],
    ];
    const restore = entries.map(([attribute, key, content]) => {
      const existing = document.head.querySelector<HTMLMetaElement>(`meta[${attribute}="${key}"]`);
      const element = existing ?? document.createElement('meta');
      const old = element.getAttribute('content');
      element.setAttribute(attribute, key);
      element.content = content;
      if (!existing) document.head.appendChild(element);
      return () => {
        if (!existing) element.remove();
        else if (old === null) element.removeAttribute('content');
        else element.content = old;
      };
    });
    return () => { document.documentElement.classList.remove('introduction-scrollbar'); document.title = previous; restore.forEach(fn => fn()); };
  }, []);
  useEffect(() => {
    if (hash) document.getElementById(hash.slice(1))?.scrollIntoView();
    else window.scrollTo(0, 0);
  }, [hash]);
  return (
    <div className="introduction-page dark text-white relative mx-auto flex w-full flex-col items-center justify-center overflow-x-clip bg-black">
      <Navbar />
      <Hero />
      <StatsStrip />
      <Features />
      <Surfaces />
      <HowItWorks />
      <PluginsMarquee />
      <Security />
      <Downloads />
      <Faq />
      <CallToAction />
      <Footer />
    </div>
  );
}
