(ns bareforge.meta.versions-test
  "Lockstep check: `deps.edn` and `bareforge.meta.versions/baredom-version`
   must agree on the BareDOM jar version. They can't share a `def`
   (one is EDN, one is CLJS) so this test is the gate that fails
   loudly on a half-finished bump."
  (:require [cljs.test :refer [deftest is]]
            [cljs.reader :as edn]
            [bareforge.meta.versions :as versions]))

(deftest deps-edn-baredom-matches-versions-cljs
  (let [fs       (js/require "fs")
        deps     (edn/read-string (.readFileSync fs "deps.edn" "utf8"))
        from-edn (get-in deps [:deps 'com.github.avanelsas/baredom :mvn/version])]
    (is (= versions/baredom-version from-edn)
        (str "deps.edn pins BareDOM at " (pr-str from-edn) " but "
             "src/bareforge/meta/versions.cljs has " (pr-str versions/baredom-version)
             ". Bump both in lockstep — see CLAUDE.md 'Onboarding a new "
             "BareDOM component'."))))
