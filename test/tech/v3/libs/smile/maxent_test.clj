(ns tech.v3.libs.smile.maxent-test
  (:require [clojure.test :as t :refer [deftest is]]
            [tech.v3.dataset :as ds]
            [tech.v3.dataset.modelling :as ds-mod]
            [tech.v3.libs.smile.maxent :as maxent]
            [tech.v3.libs.smile.nlp :as nlp]
            [tech.v3.ml :as ml]))


(defn get-reviews []
  (->
   (ds/->dataset "test/data/reviews.csv.gz" {:key-fn keyword })
   (ds/select-columns [:Text :Score])
   (nlp/count-vectorize :Text :bow nlp/default-text->bow)
   (maxent/bow->sparse-array :bow :sparse  #(nlp/->vocabulary-top-n % 1000))
   (ds-mod/set-inference-target :Score)))

(deftest test-maxent-multinomial []
  (let [reviews (get-reviews)
        trained-model (ml/train reviews {:model-type :maxent-multinomial
                                         :sparse-column :sparse
                                         :p 1000})

        prediction (ml/predict (get-reviews) trained-model)
        ]
    (is (= 1000 (count  (prediction :Score))))
    (is (= 1 (get (first (:bow reviews)) "sweet")  ))
    (is (= [120 243 453] (take 3 (-> reviews
                                     :sparse
                                     first))))
    (is (= 1001 (-> trained-model
                    :model-data
                    .coefficients
                    first
                    count)))))

(deftest test-maxent-binomial []
  (let [reviews
        (-> (get-reviews)
         (ds/filter-column :Score
                           (fn [score]
                             (or (= score 1)
                                 (= score 2)))

                           ))
        trained-model (ml/train reviews {:model-type :maxent-binomial
                                         :sparse-column :sparse
                                         :p 1000})]
    trained-model
     (is (= 1001 (-> trained-model
                     :model-data
                     .coefficients
                     count)))))
