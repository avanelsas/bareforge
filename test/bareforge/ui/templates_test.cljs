(ns bareforge.ui.templates-test
  (:require [cljs.test :refer [deftest is testing]]
            [bareforge.ui.templates :as templates]
            [bareforge.ui.theme-editor :as theme-editor]))

(deftest every-template-has-required-fields
  (doseq [t templates/templates]
    (is (keyword? (:id t)))
    (is (string?  (:label t)))
    (is (keyword? (:category t))
        (str (:label t) " is missing :category"))
    (is (string?  (:description t)))
    (is (fn?      (:build t))
        (str (:label t) "'s :build must be a thunk"))))

(deftest every-category-is-declared
  (let [allowed (set (keys templates/category-labels))]
    (doseq [t templates/templates]
      (is (contains? allowed (:category t))
          (str (:label t) " uses unknown category " (:category t))))))

(deftest templates-in-category-all-returns-everything
  (is (= (count templates/templates)
         (count (templates/templates-in-category :all)))
      ":all returns the full registry in declared order"))

(deftest templates-in-category-filters
  (testing "every category returns only its members"
    (doseq [cat [:landing :docs :dashboard :demo]]
      (let [hits (templates/templates-in-category cat)]
        (is (every? #(= cat (:category %)) hits)
            (str "templates-in-category " cat
                 " leaked an entry from another category"))))))

(deftest templates-in-category-unknown-returns-empty
  (is (= [] (templates/templates-in-category :no-such-cat))))

(deftest landing-category-has-the-original-eight
  (let [landing-ids (set (map :id (templates/templates-in-category :landing)))]
    (is (= #{:saas-hero :bento-features :scroll-story :pricing-table
             :testimonials :timeline :contact :full-landing-page}
           landing-ids)
        "the eight pre-existing realistic-starter templates are
         categorised as :landing")))

(deftest demo-category-only-has-kinetic-showcase
  (is (= [:kinetic-showcase]
         (mapv :id (templates/templates-in-category :demo)))))

(deftest docs-and-dashboard-have-content
  (is (pos? (count (templates/templates-in-category :docs))))
  (is (pos? (count (templates/templates-in-category :dashboard)))))

(deftest every-builder-returns-a-document
  (testing "each thunk produces a document with a populated root"
    (doseq [t templates/templates]
      (let [doc ((:build t))]
        (is (some? (:root doc))
            (str (:label t) " produced a doc without :root"))
        (is (pos? (count (get-in doc [:root :slots "default"])))
            (str (:label t)
                 " produced a doc with no children — the build thunk
                  did nothing"))))))

(deftest theme-presets-are-real
  (testing "every :theme-preset on a template names one of the eight
            BareDOM built-in presets — drift fails the test loudly
            instead of silently falling back to default"
    (let [allowed (set theme-editor/presets)]
      (doseq [t templates/templates
              :let [p (:theme-preset t)]
              :when p]
        (is (contains? allowed p)
            (str (:label t) " uses unknown theme preset " p))))))
