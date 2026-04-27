(ns bareforge.export.plugin-test
  (:require [cljs.test :refer [deftest is]]
            [bareforge.export.plugin :as plugin]))

(deftest safe-zip-path-passes-relative-paths
  (is (true? (plugin/safe-zip-path? "src/app/core.cljs")))
  (is (true? (plugin/safe-zip-path? "index.html")))
  (is (true? (plugin/safe-zip-path? "vendor/baredom/x-button.js")))
  (is (true? (plugin/safe-zip-path? "a.b/c-d/e_f.txt")))
  (is (true? (plugin/safe-zip-path? "deep/very/nested/file"))))

(deftest safe-zip-path-rejects-absolute
  (is (false? (plugin/safe-zip-path? "/etc/passwd")))
  (is (false? (plugin/safe-zip-path? "/var/tmp/x"))))

(deftest safe-zip-path-rejects-traversal
  (is (false? (plugin/safe-zip-path? "../escape")))
  (is (false? (plugin/safe-zip-path? "a/../b")))
  (is (false? (plugin/safe-zip-path? "a/b/../../c"))))

(deftest safe-zip-path-rejects-nul-bytes
  (is (false? (plugin/safe-zip-path? "a\u0000b"))))

(deftest safe-zip-path-rejects-windows-drive-prefixes
  (is (false? (plugin/safe-zip-path? "C:\\x")))
  (is (false? (plugin/safe-zip-path? "c:foo"))))

(deftest safe-zip-path-rejects-backslashes
  (is (false? (plugin/safe-zip-path? "src\\foo.txt"))
      "rejecting backslashes prevents Windows-style traversal"))

(deftest safe-zip-path-rejects-empty-and-non-string
  (is (false? (plugin/safe-zip-path? "")))
  (is (false? (plugin/safe-zip-path? nil)))
  (is (false? (plugin/safe-zip-path? 42))))

(deftest assert-safe-zip-path-throws-with-data
  (let [thrown (try (plugin/assert-safe-zip-path! "../x") nil
                    (catch :default e e))]
    (is (some? thrown))
    (is (= :unsafe-zip-path (:error (ex-data thrown))))
    (is (= "../x" (:path (ex-data thrown))))))

(deftest assert-safe-zip-path-passes-clean-path
  (plugin/assert-safe-zip-path! "src/app/core.cljs")
  (is true "clean path passes silently"))
