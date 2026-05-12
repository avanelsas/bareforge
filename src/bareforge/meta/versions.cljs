(ns bareforge.meta.versions
  "Single source of truth for the BareDOM version Bareforge's export
   paths emit. Every hard-coded `2.x.y` string outside `deps.edn` used
   to drift on its own — the HTML export, the bundle-zip export, and
   the ClojureScript-project export each carried their own copy.
   Centralising here means a BareDOM bump is one edit here + one edit
   in `deps.edn` (which can't import this def because it's EDN, not
   CLJS).

   When updating BareDOM, update BOTH `deps.edn` AND this file in
   lockstep. See the 'Onboarding a new BareDOM component' section of
   CLAUDE.md for the full recipe.")

(def baredom-version
  "Version of `com.github.avanelsas/baredom` used by every
   Bareforge-emitted artefact: CDN URL in HTML export, vendored jar
   path in bundle export, `deps.edn` inside an exported CLJS project.
   Keep in lockstep with `deps.edn`'s BareDOM entry."
  "3.0.0")
