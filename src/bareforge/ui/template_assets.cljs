(ns bareforge.ui.template-assets
  "Curated SVG illustrations bundled with the starter templates.
   Each illustration is encoded as a `data:image/svg+xml;utf8,…`
   data URI, which is the only `data:` form the load-boundary
   sanitiser whitelists (see `doc.sanitize/safe-url?`). Self-contained
   vector art keeps templates dependency-free — no CDN, no broken
   image links, identical bytes online and offline.

   Conventions for adding a new illustration:
   - Author the SVG with single-quoted attributes (`fill='white'`).
     The encoder rewrites any double quotes, but starting in single
     quotes keeps the source the same string the browser sees.
   - Pick a `viewBox` that matches the `ratio` you intend to set on
     the consuming `x-image` so the box doesn't crop or pad.
   - Avoid `<script>`, `<foreignObject>`, `<iframe>`, on-event
     handlers, and `javascript:` URLs — `doc.sanitize/safe-svg-fragment?`
     would refuse the document on load."
  (:require [clojure.string :as str]))

(defn- svg-data-uri
  "Encode `svg` source as a data URI suitable for `<x-image src=…>`.
   Single-quotes the attributes (the source already does, but coerce
   stray double quotes for safety) and percent-encodes the small set
   of characters that break a `data:image/svg+xml;utf8,` payload —
   `#` (CSS colors), `&` (entities), `?` (query separator), and
   newlines (some browsers refuse multi-line data URIs)."
  [svg]
  (str "data:image/svg+xml;utf8,"
       (-> svg
           (str/replace "\"" "'")
           (str/replace "#" "%23")
           (str/replace "&" "%26")
           (str/replace "?" "%3F")
           (str/replace "\n" ""))))

(def ^:private product-mockup-svg
  "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 600 360' role='img' aria-label='Product dashboard mockup'>
     <defs>
       <linearGradient id='pmg1' x1='0' y1='0' x2='1' y2='1'>
         <stop offset='0%' stop-color='#6366f1'/>
         <stop offset='100%' stop-color='#ec4899'/>
       </linearGradient>
       <linearGradient id='pmg2' x1='0' y1='0' x2='0' y2='1'>
         <stop offset='0%' stop-color='#06b6d4'/>
         <stop offset='100%' stop-color='#0ea5e9'/>
       </linearGradient>
     </defs>
     <circle cx='460' cy='180' r='200' fill='url(#pmg1)' opacity='0.12'/>
     <rect x='140' y='60' width='320' height='240' rx='20' fill='white' stroke='#e5e7eb' stroke-width='1.5'/>
     <line x1='140' y1='100' x2='460' y2='100' stroke='#f1f5f9' stroke-width='1'/>
     <circle cx='162' cy='80' r='4' fill='#cbd5e1'/>
     <circle cx='178' cy='80' r='4' fill='#cbd5e1'/>
     <circle cx='194' cy='80' r='4' fill='#cbd5e1'/>
     <rect x='220' y='75' width='180' height='10' rx='5' fill='#f1f5f9'/>
     <line x1='180' y1='100' x2='180' y2='300' stroke='#f1f5f9' stroke-width='1'/>
     <rect x='152' y='120' width='14' height='14' rx='3' fill='#cbd5e1'/>
     <rect x='152' y='148' width='14' height='14' rx='3' fill='#cbd5e1'/>
     <rect x='152' y='176' width='14' height='14' rx='3' fill='url(#pmg1)'/>
     <rect x='152' y='204' width='14' height='14' rx='3' fill='#cbd5e1'/>
     <rect x='152' y='232' width='14' height='14' rx='3' fill='#cbd5e1'/>
     <rect x='200' y='120' width='100' height='8' rx='2' fill='#cbd5e1'/>
     <rect x='200' y='138' width='160' height='22' rx='4' fill='#0f172a'/>
     <rect x='200' y='170' width='80' height='8' rx='2' fill='#10b981'/>
     <path d='M 200 260 Q 230 230 260 245 T 320 220 T 380 235 T 440 200' stroke='url(#pmg1)' stroke-width='3' fill='none' stroke-linecap='round'/>
     <path d='M 200 260 Q 230 230 260 245 T 320 220 T 380 235 T 440 200 L 440 290 L 200 290 Z' fill='url(#pmg1)' opacity='0.12'/>
     <rect x='420' y='40' width='150' height='62' rx='14' fill='white' stroke='#e5e7eb' stroke-width='1.5'/>
     <circle cx='446' cy='71' r='12' fill='url(#pmg2)'/>
     <rect x='466' y='62' width='80' height='7' rx='2' fill='#cbd5e1'/>
     <rect x='466' y='76' width='60' height='6' rx='2' fill='#e5e7eb'/>
     <rect x='30' y='240' width='150' height='80' rx='14' fill='white' stroke='#e5e7eb' stroke-width='1.5'/>
     <rect x='50' y='258' width='50' height='8' rx='2' fill='#cbd5e1'/>
     <rect x='50' y='274' width='110' height='14' rx='3' fill='#0f172a'/>
     <rect x='50' y='298' width='100' height='10' rx='5' fill='url(#pmg1)' opacity='0.85'/>
   </svg>")

(def ^:private narrative-arc-svg
  "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 600 240' role='img' aria-label='A flowing arc connecting story milestones'>
     <defs>
       <linearGradient id='nag1' x1='0' y1='0' x2='1' y2='0'>
         <stop offset='0%' stop-color='#f59e0b'/>
         <stop offset='50%' stop-color='#ef4444'/>
         <stop offset='100%' stop-color='#a855f7'/>
       </linearGradient>
     </defs>
     <ellipse cx='80' cy='130' rx='130' ry='42' fill='#f59e0b' opacity='0.10'/>
     <ellipse cx='520' cy='110' rx='130' ry='42' fill='#a855f7' opacity='0.10'/>
     <path d='M 60 180 Q 200 40 300 130 T 540 70' stroke='url(#nag1)' stroke-width='4' fill='none' stroke-linecap='round'/>
     <circle cx='60' cy='180' r='11' fill='white' stroke='#f59e0b' stroke-width='3'/>
     <circle cx='60' cy='180' r='3' fill='#f59e0b'/>
     <circle cx='220' cy='86' r='11' fill='white' stroke='#ef4444' stroke-width='3'/>
     <circle cx='220' cy='86' r='3' fill='#ef4444'/>
     <circle cx='380' cy='130' r='11' fill='white' stroke='#a855f7' stroke-width='3'/>
     <circle cx='380' cy='130' r='3' fill='#a855f7'/>
     <circle cx='540' cy='70' r='15' fill='url(#nag1)'/>
     <circle cx='540' cy='70' r='5' fill='white'/>
     <circle cx='150' cy='208' r='3' fill='#f59e0b' opacity='0.45'/>
     <circle cx='450' cy='40' r='4' fill='#a855f7' opacity='0.45'/>
     <circle cx='300' cy='208' r='2' fill='#ef4444' opacity='0.45'/>
     <circle cx='480' cy='180' r='3' fill='#f59e0b' opacity='0.40'/>
   </svg>")

(def ^:private editorial-spread-svg
  "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 600 280' role='img' aria-label='Two editorial pages with a pull-quote'>
     <defs>
       <linearGradient id='esg1' x1='0' y1='0' x2='1' y2='1'>
         <stop offset='0%' stop-color='#d97706'/>
         <stop offset='100%' stop-color='#b45309'/>
       </linearGradient>
       <linearGradient id='esg2' x1='0' y1='0' x2='0' y2='1'>
         <stop offset='0%' stop-color='#fef3c7'/>
         <stop offset='100%' stop-color='#fde68a'/>
       </linearGradient>
     </defs>
     <rect x='0' y='0' width='600' height='280' rx='12' fill='url(#esg2)' opacity='0.5'/>
     <g transform='rotate(-3 200 140)'>
       <rect x='80' y='40' width='240' height='200' rx='8' fill='white' stroke='#e7e5e4' stroke-width='1.5'/>
       <rect x='100' y='60' width='80' height='12' rx='3' fill='url(#esg1)'/>
       <rect x='100' y='90' width='200' height='6' rx='2' fill='#d6d3d1'/>
       <rect x='100' y='104' width='180' height='6' rx='2' fill='#d6d3d1'/>
       <rect x='100' y='118' width='160' height='6' rx='2' fill='#d6d3d1'/>
       <rect x='100' y='140' width='200' height='80' rx='6' fill='url(#esg1)' opacity='0.18'/>
       <circle cx='140' cy='180' r='18' fill='url(#esg1)' opacity='0.7'/>
     </g>
     <g transform='rotate(4 400 140)'>
       <rect x='300' y='30' width='240' height='220' rx='8' fill='white' stroke='#e7e5e4' stroke-width='1.5'/>
       <rect x='320' y='50' width='200' height='14' rx='3' fill='#1c1917'/>
       <rect x='320' y='80' width='200' height='6' rx='2' fill='#d6d3d1'/>
       <rect x='320' y='94' width='180' height='6' rx='2' fill='#d6d3d1'/>
       <rect x='320' y='108' width='200' height='6' rx='2' fill='#d6d3d1'/>
       <rect x='320' y='122' width='150' height='6' rx='2' fill='#d6d3d1'/>
       <rect x='320' y='140' width='3' height='80' fill='url(#esg1)'/>
       <rect x='335' y='140' width='180' height='10' rx='2' fill='#1c1917'/>
       <rect x='335' y='160' width='160' height='6' rx='2' fill='#a8a29e'/>
       <rect x='335' y='176' width='170' height='6' rx='2' fill='#a8a29e'/>
       <rect x='335' y='192' width='130' height='6' rx='2' fill='#a8a29e'/>
     </g>
     <path d='M 36 80 Q 36 50 60 50 L 56 60 Q 46 60 46 76 L 50 76 L 50 96 L 36 96 Z' fill='url(#esg1)' opacity='0.55'/>
     <path d='M 64 80 Q 64 50 88 50 L 84 60 Q 74 60 74 76 L 78 76 L 78 96 L 64 96 Z' fill='url(#esg1)' opacity='0.55'/>
   </svg>")

(def product-mockup-uri
  "5:3 hero illustration — abstract product dashboard with a chart,
   side-rail, and floating callout cards. Neutral palette, works
   against any theme background. Pair with `:ratio \"5/3\"`."
  (svg-data-uri product-mockup-svg))

(def narrative-arc-uri
  "5:2 banner — a flowing arc through three milestone dots into a
   fourth, terminal node. Reads as 'a journey'. Pair with
   `:ratio \"5/2\"`."
  (svg-data-uri narrative-arc-svg))

(def editorial-spread-uri
  "15:7 hero — two slightly-rotated editorial pages on a warm
   parchment background, with a serif pull-quote glyph. Designed to
   complement the `warm-mineral` theme. Pair with `:ratio \"15/7\"`."
  (svg-data-uri editorial-spread-svg))
