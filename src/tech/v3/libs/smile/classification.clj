(ns tech.v3.libs.smile.classification
  "Namespace to require to enable a set of smile classification models."
  (:require [tech.v3.datatype :as dtype]
            [tech.v3.datatype.protocols :as dtype-proto]
            [tech.v3.dataset :as ds]
            [tech.v3.dataset.modelling :as ds-mod]
            [tech.v3.dataset.utils :as ds-utils]
            [tech.v3.tensor :as dtt]
            [tech.v3.ml.gridsearch :as ml-gs]
            [tech.v3.ml.model :as model]
            [tech.v3.ml :as ml]
            [tech.v3.libs.smile.protocols :as smile-proto]
            [tech.v3.libs.smile.data :as smile-data]
            [tech.v3.datatype.errors :as errors]
            )
  (:import [smile.classification SoftClassifier AdaBoost LogisticRegression
            DecisionTree RandomForest KNN GradientTreeBoost]
           [smile.base.cart SplitRule]
           [smile.data.formula Formula]
           [smile.data DataFrame]
           [java.util Properties List]
           [tech.v3.datatype ObjectReader]))


(set! *warn-on-reflection* true)

(defn- tuple-predict-posterior
  [^SoftClassifier model ds options n-labels]
  (let [df (smile-data/dataset->smile-dataframe ds)
        n-rows (ds/row-count ds)]
    (smile-proto/initialize-model-formula! model ds)
    (reify
      dtype-proto/PShape
      (shape [rdr] [n-rows n-labels])
      ObjectReader
      (lsize [rdr] n-rows)
      (readObject [rdr idx]
        (let [posterior (double-array n-labels)]
          (.predict model (.get df idx) posterior)
          posterior)))))


(defn- double-array-predict-posterior
  [^SoftClassifier model ds options n-labels]
  (let [value-reader (ds/value-reader ds)
        n-rows (ds/row-count ds)]
    (reify
      dtype-proto/PShape
      (shape [rdr] [n-rows n-labels])
      ObjectReader
      (lsize [rdr] n-rows)
      (readObject [rdr idx]
        (let [posterior (double-array n-labels)]
          (.predict model (double-array (value-reader idx)) posterior)
          posterior)))))


(defn construct-knn [^Formula formula ^DataFrame data-frame ^Properties props]
  (KNN/fit (.toArray (.matrix  formula data-frame false))
           (.toIntArray  (.y formula data-frame))))


(def split-rule-lookup-table
  {:gini SplitRule/GINI
   :entropy SplitRule/ENTROPY
   :classification-error  SplitRule/CLASSIFICATION_ERROR})

(def ^:private classifier-metadata
  {:ada-boost
   {:name :ada-boost
    :options [{:name :trees
               :type :int32
               :default 500}
              {:name :max-depth
               :type :int32
               :default 200}
              {:name :max-nodes
               :type :int32
               :default 6}
              {:name :node-size
               :type :int32
               :default 1}]
    :gridsearch-options {:trees (ml-gs/linear 2 50 10 :int64)
                         :max-nodes (ml-gs/linear 4 1000 20 :int64)}
    :property-name-stem "smile.databoost"
    :constructor #(AdaBoost/fit ^Formula %1 ^DataFrame %2 ^Properties %3)
    :predictor tuple-predict-posterior}
   :logistic-regression
   {:name :logistic-regression
    :options [{:name :lambda
               :type :float64
               :default 0.1}
              {:name :tolerance
               :type :float64
               :default 1e-5}
              {:name :max-iterations
               :type :int32
               :default 500}]
    :gridsearch-options {:lambda (ml-gs/linear 1e-3 1e2 30)
                         :tolerance (ml-gs/linear 1e-9 1e-1 20)
                         :max-iterations (ml-gs/linear 1e2 1e4 20 :int64)}
    :property-name-stem "smile.logistic"
    :constructor #(LogisticRegression/fit ^Formula %1 ^DataFrame %2 ^Properties %3)
    :predictor double-array-predict-posterior}

   :decision-tree
   {:name :decision-tree
    :options [{:name :max-nodes
               :type :int32
               :default 100}
              {:name :node-size
               :type :int32
               :default 1}
              {:name :max-depth
               :type :int32 
               :default 20}
              {:name :split-rule
               :type :string
               :lookup-table split-rule-lookup-table
               :default :gini}]
    :gridsearch-options {:max-nodes (ml-gs/linear 10 1000 30)
                         :node-size (ml-gs/linear 1 20 20)
                         :max-depth (ml-gs/linear 1 50 20 )
                         :split-rule (ml-gs/categorical [:gini :entropy :classification-error] )

                         }
    :property-name-stem "smile.cart"
    :constructor #(DecisionTree/fit ^Formula %1 ^DataFrame %2  ^Properties %3)
    :predictor tuple-predict-posterior

    }

   ;; :fld {:attributes #{:projection}
   ;;       :class-name "FLD"
   ;;       :datatypes #{:float64-array}
   ;;       :name :fld
   ;;       :options [{:name :L
   ;;                  :type :int32
   ;;                  :default -1}
   ;;                 {:name :tolerance
   ;;                  :type :float64
   ;;                  :default 1e-4}]}
   :gradient-tree-boost
   {:class-name "GradientTreeBoost"
    :name :gradient-tree-boost
    :options [{:name :ntrees
               :type :int32
               :default 500}
              {:name :max-nodes
               :type :int32
               :default 6}
              {:name :shrinkage
               :type :float64
               :default 0.005}
              {:name :sampling-fraction
               :type :float64
               :default 0.7}]
    :constructor #(GradientTreeBoost/fit ^Formula %1 ^DataFrame %2  ^Properties %3 )
    :predictor tuple-predict-posterior
    }
   :knn {

         :name :knn
         :options [{:name :k
                    :type :int32
                    :default 5}
                   ]
         :constructor #(construct-knn ^Formula %1 ^DataFrame %2  ^Properties %3)
         :predictor double-array-predict-posterior
         :property-name-stem "smile.knn"
         :gridsearch-options {:k (ml-gs/categorical [2 100])}}

   ;; :naive-bayes {:attributes #{:online :probabilities}
   ;;               :class-name "NaiveBayes"
   ;;               :datatypes #{:float64-array :sparse}
   ;;               :name :naive-bayes
   ;;               :options [{:name :model
   ;;                          :type :enumeration
   ;;                          :class-type NaiveBayes$Model
   ;;                          :lookup-table {
   ;;                                         ;; Users have to provide probabilities for this to work.
   ;;                                         ;; :general NaiveBayes$Model/GENERAL

   ;;                                         :multinomial NaiveBayes$Model/MULTINOMIAL
   ;;                                         :bernoulli NaiveBayes$Model/BERNOULLI
   ;;                                         :polyaurn NaiveBayes$Model/POLYAURN}
   ;;                          :default :multinomial}
   ;;                         {:name :num-classes
   ;;                          :type :int32
   ;;                          :default utils/options->num-classes}
   ;;                         {:name :input-dimensionality
   ;;                          :type :int32
   ;;                          :default utils/options->feature-ecount}
   ;;                         {:name :sigma
   ;;                          :type :float64
   ;;                          :default 1.0}]
   ;;               :gridsearch-options {:model (ml-gs/nominative [:multinomial :bernoulli :polyaurn])
   ;;                                    :sigma (ml-gs/exp [1e-4 0.2])}}
   ;; :neural-network {:attributes #{:online :probabilities}
   ;;                  :class-name "NeuralNetwork"
   ;;                  :datatypes #{:float64-array}
   ;;                  :name :neural-network}
   ;; :platt-scaling {:attributes #{}
   ;;                 :class-name "PlattScaling"
   ;;                 :datatypes #{:double}
   ;;                 :name :platt-scaling}


   ;; ;;Lots of discriminant analysis
   ;; :linear-discriminant-analysis
   ;; {:attributes #{:probabilities}
   ;;  :class-name "LDA"
   ;;  :datatypes #{:float64-array}
   ;;  :name :lda
   ;;  :options [{:name :prioiri
   ;;             :type :float64-array
   ;;             :default nil}
   ;;            {:name :tolerance
   ;;             :default 1e-4
   ;;             :type :float64}]
   ;;  :gridsearch-options {:tolerance (ml-gs/linear [1e-9 1e-2])}}


   ;; :quadratic-discriminant-analysis
   ;; {:attributes #{:probabilities}
   ;;  :class-name "QDA"
   ;;  :datatypes #{:float64-array}
   ;;  :name :qda
   ;;  :options [{:name :prioiri
   ;;             :type :float64-array
   ;;             :default nil}
   ;;            {:name :tolerance
   ;;             :default 1e-4
   ;;             :type :float64}]
   ;;  :gridsearch-options {:tolerance (ml-gs/linear [1e-9 1e-2])}}


   ;; :regularized-discriminant-analysis
   ;; {:attributes #{:probabilities}
   ;;  :class-name "RDA"
   ;;  :datatypes #{:float64-array}
   ;;  :name :rda
   ;;  :options [{:name :prioiri
   ;;             :type :float64-array
   ;;             :default nil}
   ;;            {:name :alpha
   ;;             :type :float64
   ;;             :default 0.0 }
   ;;            {:name :tolerance
   ;;             :default 1e-4
   ;;             :type :float64}]
   ;;  :gridsearch-options {:tolerance (ml-gs/linear [1e-9 1e-2])
   ;;                       :alpha (ml-gs/linear [0.0 1.0])}}


   :random-forest {:name :random-forest
                   :constructor #(RandomForest/fit ^Formula %1 ^DataFrame %2  ^Properties %3)
                   :predictor tuple-predict-posterior
                   :options [{:name :trees :type :int32 :default 500}
                             {:name :mtry :type :int32 :default 0}
                             {:name :split-rule
                              :type :string
                              :lookup-table split-rule-lookup-table
                              :default :gini}
                             {:name :max-depth :type :int32 :default 20}
                             {:name :max-nodes :type :int32 :default (fn [dataset props] (unchecked-int (max 5 (/ (ds/row-count dataset) 5))))

                              }
                             {:name :node-size :type :int32 :default 5}
                             {:name :sample-rate :type :float32 :default 1.0}
                             {:name :class-weight :type :string :default nil}
                             ]
                   :property-name-stem "smile.random.forest"}
   ;; :rbf-network {:attributes #{}
   ;;               :class-name "RBFNetwork"
   ;;               :datatypes #{}
   ;;               :name :rbf-network}


   })


(defmulti ^:private model-type->classification-model
  (fn [model-type] model-type))


(defmethod model-type->classification-model :default
  [model-type]
  (if-let [retval (get classifier-metadata model-type)]
    retval
    (throw (ex-info "Failed to find classification model"
                    {:model-type model-type
                     :available-types (keys classifier-metadata)}))))


(defn- train
  [feature-ds label-ds options]
  (let [entry-metadata (model-type->classification-model
                        (model/options->model-type options))

        _ (errors/when-not-error
           (ds-mod/inference-target-label-map label-ds)
           "In classification, the target column needs to be categorical and having been transformed to numeric.
See tech.v3.dataset/categorical->number.
"
           )
        target-colname (first (ds/column-names label-ds))
        feature-colnames (ds/column-names feature-ds)
        formula (smile-proto/make-formula (ds-utils/column-safe-name target-colname)
                                          (map ds-utils/column-safe-name
                                               feature-colnames))
        dataset (merge feature-ds
                       (ds/update-columnwise
                        label-ds :all
                        dtype/elemwise-cast :int32))
        data (smile-data/dataset->smile-dataframe dataset)
        properties (smile-proto/options->properties entry-metadata dataset options)
        ctor (:constructor entry-metadata)
        model (ctor formula data properties)]
    (model/model->byte-array model)))


(defn- thaw
  [model-data]
  (model/byte-array->model model-data))


(defn- predict
  [feature-ds thawed-model {:keys [target-columns
                                   target-categorical-maps
                                   options]}]
  (let [entry-metadata (model-type->classification-model
                        (model/options->model-type options))
        target-colname (first target-columns)
        n-labels (-> (get target-categorical-maps target-colname)
                     :lookup-table
                     count)
        _ (errors/when-not-error (pos? n-labels) "n-labels equals 0. Something is wrong with the :lookup-table")
        predictor (:predictor entry-metadata)
        predictions (predictor thawed-model feature-ds options n-labels)]
    (-> predictions
        (dtt/->tensor)
        (model/finalize-classification (ds/row-count feature-ds)
                                       target-colname
                                       target-categorical-maps))))

(doseq [[reg-kwd reg-def] classifier-metadata]
  (ml/define-model! (keyword "smile.classification" (name reg-kwd))
    train predict {:thaw-fn thaw
                   :hyperparameters (:gridsearch-options reg-def)}))


(comment
  (do
    (require '[tech.v3.dataset.column-filters :as cf])
    (require '[tech.v3.dataset.modelling :as ds-mod])
    (require '[tech.v3.ml.loss :as loss])
    (def src-ds (ds/->dataset "test/data/iris.csv"))
    (def ds (->  src-ds
                 (ds/categorical->number cf/categorical)
                 (ds-mod/set-inference-target "species")))
    (def feature-ds (cf/feature ds))
    (def split-data (ds-mod/train-test-split ds))
    (def train-ds (:train-ds split-data))
    (def test-ds (:test-ds split-data))
    (def model (ml/train train-ds {:model-type :smile.classification/gradient-tree-boost}))
    (def prediction (ml/predict test-ds model)))

  )
