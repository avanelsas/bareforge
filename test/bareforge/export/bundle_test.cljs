(ns bareforge.export.bundle-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.doc.model :as m]
            [bareforge.doc.ops :as ops]
            [bareforge.export.bundle :as bundle]))

;; --- initial-module-set --------------------------------------------------

(deftest initial-module-set-always-includes-base-and-xtheme
  (let [doc (m/empty-document)
        fs  (bundle/initial-module-set doc)]
    (is (contains? fs "base.js")
        "the shared BareDOM runtime is always required")
    (is (contains? fs "x-theme.js")
        "the outer <x-theme> wrapper is always required")
    (is (contains? fs "x-container.js")
        "the root container from empty-document is included")))

(deftest initial-module-set-adds-document-tags
  (let [d0       (m/empty-document)
        {d1 :doc} (ops/insert-new d0 "root" "default" 0 "x-button")
        {d2 :doc} (ops/insert-new d1 "root" "default" 1 "x-typography")
        fs       (bundle/initial-module-set d2)]
    (is (contains? fs "x-button.js"))
    (is (contains? fs "x-typography.js"))
    (is (contains? fs "x-container.js"))))

;; --- module-filename -----------------------------------------------------

(deftest module-filename-appends-dot-js
  (is (= "x-button.js" (bundle/module-filename "x-button")))
  (is (= "x-button.js" (bundle/module-filename :x-button))))

;; --- extract-relative-imports -------------------------------------------

(deftest extract-imports-static-from
  (is (= #{"./base.js"}
         (bundle/extract-relative-imports
          "import { foo } from './base.js';"))))

(deftest extract-imports-static-bare
  (is (= #{"./base.js"}
         (bundle/extract-relative-imports
          "import './base.js';"))))

(deftest extract-imports-dynamic
  (is (= #{"./x-button.js"}
         (bundle/extract-relative-imports
          "const m = await import('./x-button.js');"))))

(deftest extract-imports-multiple-and-deduped
  (let [src (str "import './base.js';\n"
                 "import { a } from \"./helpers.js\";\n"
                 "import './base.js';\n")]
    (is (= #{"./base.js" "./helpers.js"}
           (bundle/extract-relative-imports src)))))

(deftest extract-imports-ignores-absolute
  (testing "only relative imports (./, ../) are considered"
    (is (= #{}
           (bundle/extract-relative-imports
            "import 'https://example.com/foo.js'; import 'bare-package';")))))

(deftest extract-imports-handles-parent-paths
  (is (= #{"../base.js"}
         (bundle/extract-relative-imports "import '../base.js';"))))

;; --- parent-dir ----------------------------------------------------------

(deftest parent-dir-root
  (is (= "" (bundle/parent-dir "base.js")))
  (is (= "" (bundle/parent-dir "x-button.js"))))

(deftest parent-dir-single-level
  (is (= "sub/" (bundle/parent-dir "sub/foo.js"))))

(deftest parent-dir-nested
  (is (= "a/b/" (bundle/parent-dir "a/b/foo.js"))))

;; --- normalize-import ----------------------------------------------------

(deftest normalize-dot-slash-at-root-strips-prefix
  (is (= "base.js" (bundle/normalize-import "" "./base.js"))))

(deftest normalize-dot-slash-in-subdir-keeps-parent
  (is (= "sub/helpers.js"
         (bundle/normalize-import "sub/" "./helpers.js"))))

(deftest normalize-dot-dot-from-subdir-pops-one-level
  (is (= "base.js"
         (bundle/normalize-import "sub/" "../base.js"))))

(deftest normalize-dot-dot-from-nested-pops-one-level
  (is (= "a/base.js"
         (bundle/normalize-import "a/b/" "../base.js"))))

(deftest normalize-dot-dot-at-root-degrades-gracefully
  ;; There is nothing above the root. Fall back to the tail so the
  ;; resolver at least points at a plausible sibling.
  (is (= "base.js"
         (bundle/normalize-import "" "../base.js"))))
