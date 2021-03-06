/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.core.check

import com.typesafe.scalalogging.StrictLogging

import io.gatling.core.check.extractor.Extractor
import io.gatling.core.session.{ Session, Expression, ExpressionWrapper, RichExpression }
import io.gatling.core.validation.{ FailureWrapper, Validation }

trait FindCheckBuilder[C <: Check[R], R, P, X] {

  def find: ValidatorCheckBuilder[C, R, P, X]
}

class DefaultFindCheckBuilder[C <: Check[R], R, P, X](extender: Extender[C, R],
                                                      preparer: Preparer[R, P],
                                                      extractor: Expression[Extractor[P, X]])
    extends FindCheckBuilder[C, R, P, X] {

  def find: ValidatorCheckBuilder[C, R, P, X] = ValidatorCheckBuilder(extender, preparer, extractor)
}

trait MultipleFindCheckBuilder[C <: Check[R], R, P, X] extends FindCheckBuilder[C, R, P, X] {

  def find(occurrence: Int): ValidatorCheckBuilder[C, R, P, X]

  def findAll: ValidatorCheckBuilder[C, R, P, Seq[X]]

  def count: ValidatorCheckBuilder[C, R, P, Int]
}

abstract class DefaultMultipleFindCheckBuilder[C <: Check[R], R, P, X](extender: Extender[C, R],
                                                                       preparer: Preparer[R, P])
    extends MultipleFindCheckBuilder[C, R, P, X] {

  def findExtractor(occurrence: Int): Expression[Extractor[P, X]]

  def findAllExtractor: Expression[Extractor[P, Seq[X]]]

  def countExtractor: Expression[Extractor[P, Int]]

  def find = find(0)

  def find(occurrence: Int): ValidatorCheckBuilder[C, R, P, X] = ValidatorCheckBuilder(extender, preparer, findExtractor(occurrence))

  def findAll: ValidatorCheckBuilder[C, R, P, Seq[X]] = ValidatorCheckBuilder(extender, preparer, findAllExtractor)

  def count: ValidatorCheckBuilder[C, R, P, Int] = ValidatorCheckBuilder(extender, preparer, countExtractor)
}

case class ValidatorCheckBuilder[C <: Check[R], R, P, X](
    extender: Extender[C, R],
    preparer: Preparer[R, P],
    extractor: Expression[Extractor[P, X]]) extends StrictLogging {

  private def transformExtractor[X2](transformation: X => X2)(e: Extractor[P, X]) =
    new Extractor[P, X2] {
      def name = e.name + " transform"
      def arity = e.arity

      def apply(prepared: P): Validation[Option[X2]] =
        try {
          e(prepared).map { extracted =>
            extracted.map(transformation)
          }
        } catch {
          case e: Exception => s"transform crashed: ${e.getMessage}".failure
        }
    }

  def transform[X2](transformation: X => X2): ValidatorCheckBuilder[C, R, P, X2] =
    copy(extractor = extractor.map(transformExtractor(transformation)))

  def transform[X2](transformation: (X, Session) => X2): ValidatorCheckBuilder[C, R, P, X2] =
    copy(extractor = session => extractor(session).map(transformExtractor(transformation(_, session))))

  private def transformOptionExtractor[X2](transformation: Option[X] => Validation[Option[X2]])(e: Extractor[P, X]) =
    new Extractor[P, X2] {
      def name = e.name + " transformOption"
      def arity = e.arity

      def apply(prepared: P): Validation[Option[X2]] =
        try {
          e(prepared).flatMap { extracted =>
            transformation(extracted)
          }
        } catch {
          case e: Exception => s"transformOption crashed: ${e.getMessage}".failure
        }
    }

  def transformOption[X2](transformation: Option[X] => Validation[Option[X2]]): ValidatorCheckBuilder[C, R, P, X2] =
    copy(extractor = extractor.map(transformOptionExtractor(transformation)))

  def transformOption[X2](transformation: (Option[X], Session) => Validation[Option[X2]]): ValidatorCheckBuilder[C, R, P, X2] =
    copy(extractor = session => extractor(session).map(transformOptionExtractor(transformation(_, session))))

  def validate(validator: Expression[Validator[X]]) = new CheckBuilder(this, validator) with SaveAs[C, R, P, X]

  def is(expected: Expression[X]) = validate(expected.map(new IsMatcher(_)))
  def not(expected: Expression[X]) = validate(expected.map(new NotMatcher(_)))
  def in(expected: Expression[Seq[X]]) = validate(expected.map(new InMatcher(_)))
  def exists = validate(new ExistsValidator[X]().expression)
  def notExists = validate(new NotExistsValidator[X]().expression)
  @deprecated("Will be removed in 2.1, use 'optional' instead", "2.0-RC3")
  def dontValidate = optional
  def optional = validate(new NoopValidator[X]().expression)
  def lessThan(expected: Expression[X])(implicit ordering: Ordering[X]) = validate(expected.map(new CompareMatcher("lessThan", "less than", ordering.lt, _)))
  def lessThanOrEqual(expected: Expression[X])(implicit ordering: Ordering[X]) = validate(expected.map(new CompareMatcher("lessThanOrEqual", "less than or equal to", ordering.lteq, _)))
  def greaterThan(expected: Expression[X])(implicit ordering: Ordering[X]) = validate(expected.map(new CompareMatcher("greaterThan", "greater than", ordering.gt, _)))
  def greaterThanOrEqual(expected: Expression[X])(implicit ordering: Ordering[X]) = validate(expected.map(new CompareMatcher("greaterThanOrEqual", "greater than or equal to", ordering.gteq, _)))
}

case class CheckBuilder[C <: Check[R], R, P, X](
    validatorCheckBuilder: ValidatorCheckBuilder[C, R, P, X],
    validator: Expression[Validator[X]],
    saveAs: Option[String] = None) {

  def build: C = {
    val base = CheckBase(validatorCheckBuilder.preparer, validatorCheckBuilder.extractor, validator, saveAs)
    validatorCheckBuilder.extender(base)
  }
}

trait SaveAs[C <: Check[R], R, P, X] { this: CheckBuilder[C, R, P, X] =>
  def saveAs(key: String): CheckBuilder[C, R, P, X] = copy(saveAs = Some(key))
}
