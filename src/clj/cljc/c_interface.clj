(ns cljc.c-interface
  (:require [clojure.java.io :as io]))

(defonce objc-selectors (atom {}))
(defn objc-register-selector! [selector types]
  (swap! objc-selectors update-in [selector] (fnil conj #{}) types))
(defn objc-reset-selectors! []
  (swap! objc-selectors (constantly {})))

(def ^:private to-objc-converters
  {:id "objc_convert_to_objc_object (~{})"
   :selector "objc_selector_get (~{})"
   :float "((float) number_get (~{}))"
   :double "number_get (~{})"
   ;; FIXME: potential data loss - char is not a wide character
   :char "(char) character_get (~{})"
   ;; FIXME: more potential data loss when casting down to smaller integers
   :signed-char "((signed char) number_get_as_integer (~{}))"
   :unsigned-char "((unsigned char) number_get_as_integer (~{}))"
   :short "((short) number_get_as_integer (~{}))"
   :unsigned-short "((unsigned short) number_get_as_integer (~{}))"
   :int "((int) number_get_as_integer (~{}))"
   :unsigned-int "((unsigned int) number_get_as_integer (~{}))"
   :long "((long) number_get_as_integer (~{}))"
   :unsigned-long "((unsigned long) number_get_as_integer (~{}))"
   :long-long "number_get_as_integer (~{})"
   :unsigned-long-long "((unsigned long long) number_get_as_integer (~{}))"
   'Boolean "truth (~{})"})
(defn to-objc-converter [type]
  (let [converter (to-objc-converters type)]
    (when-not converter
      (throw (Error. (str "Unknown C type " type))))
    converter))

(def ^:private from-objc-converters
  {:void "%s"
   :id "make_objc_object (%s)"
   :selector "make_objc_selector (%s)"
   :float "make_float (%s)"
   :double "make_float (%s)"
   :char "make_character ((cljc_unichar_t) %s)"
   :signed-char "make_integer ((long long) %s)"
   :unsigned-char "make_integer ((long long) %s)"
   :short "make_integer ((long long) %s)"
   :unsigned-short "make_integer ((long long) %s)"
   ; FIXME: We're potentially losing data in some of these unsigned conversions.
   :int "make_integer ((long long) %s)"
   :unsigned-int "make_integer ((long long) %s)"
   :long "make_integer ((long long) %s)"
   :unsigned-long "make_integer ((long long) %s)"
   :long-long "make_integer ((long long) %s)"
   :unsigned-long-long "make_integer ((long long) %s)"
   'Boolean "make_boolean (%s)"})
(defn from-objc-converter [type]
  (let [converter (from-objc-converters type)]
    (when-not converter
      (throw (Error. (str "Unknown C type " type))))
    converter))

(defn types-for-selector [selector]
  (let [typess (@objc-selectors selector)]
    (and (= (count typess) 1) (first typess))))

(defn selector-string [selector]
  (let [num-args (first selector)
        selector-kws (rest selector)]
    (if (zero? num-args)
      (name (first selector-kws))
      (apply str (map #(str (name %) ":") selector-kws)))))

(defn make-msg-send [selector target args]
  (let [types (types-for-selector selector)
        selector-kws (rest selector)
        num-args (first selector)]
    (if types
      (let [result-type (first types)
            arg-types (rest types)]
        (assert (= num-args (count arg-types) (count args)))
        (if (zero? num-args)
          (list 'c* (format (from-objc-converter result-type)
                                 (str "[objc_object_get (~{}) "
                                           (name (first selector-kws))
                                           "]"))
                target)
          (apply list 'c* (format (from-objc-converter result-type)
                                       (str "[objc_object_get (~{}) "
                                                 (apply str (map (fn [sel-kw type]
                                                                        (str (name sel-kw) ": " (to-objc-converter type)))
                                                                      selector-kws arg-types))
                                                 "]"))
                 target args)))
      (let [selector-str (selector-string selector)]
        (if (zero? num-args)
          (list 'cljc.objc/objc-msg-send target (list 'c* (str "make_objc_selector (@selector (" selector-str "))")))
          (apply list 'cljc.objc/objc-msg-send target (list 'c* (str "make_objc_selector (@selector (" selector-str "))")) args))))))

(defn deconstruct-msg-form [ys]
  [(cons (quot (count ys) 2) (take-nth 2 ys))
   (take-nth 2 (rest ys))])

(defn- objc-type [type]
  (cond
   (nil? type) "value_t*"
   (= type 'Boolean) "BOOL"
   (= type :void) "void"
   (= type :id) "id"
   (and (list? type) (= (first type) '§)) (str (second type) "*")
   :else (throw (Error. (str "Unknown type " type)))))

(defn- method-prototype [signature]
  (let [type (:type (meta signature))
        signature (drop 2 signature)
        signature-str (if (= (count signature) 1)
                        (name (first signature))
                        (let [arg-pairs (partition 2 signature)]
                          (apply str (map (fn [[sel-part arg]]
                                                 (str " " (name sel-part) ":(" (objc-type (:type (meta arg))) ")" arg))
                                          arg-pairs))))]
    (str "-(" (objc-type type) ")" signature-str)))

(defn class-interface [class-name superclass interfaces fields methods]
  (let [interfaces-str (if (empty? interfaces)
                         ""
                         (str "<" (apply str (interpose "," interfaces)) ">"))
        fields-str (if (empty? fields)
                     ""
                     (str "{\n@public\n" (apply str (map #(str "value_t *" % ";\n") fields)) "}\n"))
        method-strs (->> methods
                         (map first)
                         (map method-prototype))]
    (str "@interface " class-name ":" superclass interfaces-str
              fields-str
              (apply str (map #(str % ";\n") method-strs))
              "@end\n")))

(defn- convert-from-objc [type expr]
  (cond (or (symbol? type) (keyword? type))
        (case type
          Boolean
          (str "make_boolean (" expr ")")

          :id
          (str "make_objc_object (" expr ")")

          (throw (Error. (str "Unknown type " type))))

        (nil? type)
        expr

        (and (seq? type) (= (first type) '§))
        (str "make_objc_object (" expr ")")

        :else
        (throw (Error. (str "Unknown type " type)))))

(defn- convert-to-objc [type expr]
  (cond (or (symbol? type) (keyword? type))
        (case type
          :void
          (str "")

          :id
          (str "objc_object_get (" expr ")")

          Boolean
          (str "truth (" expr ")")

          (throw (Error. (str "Unknown type " type))))

        (nil? type)
        expr

        (and (seq? type) (= (first type) '§))
        (str "(" (second type) "*)objc_object_get (" expr ")")

        :else
        (throw (Error. (str "Unknown type " type)))))

(defn- method-funcall [signature]
  (let [signature (drop 2 signature)
        args (map second (partition 2 signature))
        arity (count args)]
    (str "FUNCALL" (if (< arity 3)
                          (inc arity)
                          "n")
              " ((closure_t*)~{}, "
              (convert-from-objc :id "self")
              (apply str (map (fn [arg]
                                     (str ", " (convert-from-objc (:type (meta arg)) arg)))
                                   (take 2 args)))
              (if (>= arity 3)
                (str "(value_t*[]) {"
                          (apply str (map (fn [arg]
                                                 (str (convert-from-objc (:type (meta arg)) arg) ", "))
                                               (drop 2 args)))
                          "}")
                "")
              ")")))

(defn class-implementation [class-name fields methods]
  (let [method-strs (map (fn [[signature body]]
                           (let [type (:type (meta signature))]
                             (str (method-prototype signature) " {\n"
                                       "value_t *result = " (method-funcall signature) ";\n"
                                       "return " (convert-to-objc type "result") ";\n"
                                       "}\n")))
                         methods)]
    (str "@implementation " class-name "\n"
              (apply str method-strs)
              "@end\n")))