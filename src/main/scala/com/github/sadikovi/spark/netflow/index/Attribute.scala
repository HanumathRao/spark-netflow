/*
 * Copyright 2016 sadikovi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.sadikovi.spark.netflow.index

import java.util.{HashSet => JHashSet}

import scala.reflect.ClassTag

/**
 * [[Attribute]] interface to collect and check statistics. Included support of different
 * combinations of collected parameters: count, min/max, and set of values through bit vector. Here
 * are some common flags: 7 - enable all parameters, 1 - enable count, 6 - enable min/max and set.
 * In order to create attribute comparison function is required similar the `lt` function used in
 * `sortWith` method. Name must unique to the attribute.
 */
class Attribute[T](
    val name: String,
    val lt: (T, T) => Boolean,
    val flags: Byte,
    val isNullable: Boolean)(implicit tag: ClassTag[T]) {
  private val clazz = tag.runtimeClass
  private var count: Long = if (isCountEnabled) 0 else Long.MinValue
  private var min: T = _
  private var max: T = _
  private var set: JHashSet[T] = if (isSetEnabled) new JHashSet() else null
  private var hasNull: Boolean = false

  require(name != null && name.nonEmpty, "Attribute name is empty")

  private def isEnabled(flag: Byte): Boolean = {
    (flags & flag) == flag
  }

  /** Check if count is collected by this attribute */
  def isCountEnabled(): Boolean = isEnabled(1)

  /** Check if min/max is collected by this attribute */
  def isMinMaxEnabled(): Boolean = isEnabled(2)

  /** Check if set is collected by this attribute */
  def isSetEnabled(): Boolean = isEnabled(4)

  def numStatistics(): Int = {
    Array(isCountEnabled, isMinMaxEnabled, isSetEnabled).filter(_ & true).length
  }

  /** Add value to the attribute, it automatically checks all available modes */
  def addValue(unresolvedValue: Any): Unit = {
    if (unresolvedValue != null) {
      require(StatisticsUtils.softCompare(unresolvedValue.getClass(), getClassTag()),
        s"Value '$unresolvedValue' does not match runtime class '${getClassTag()}'")
    }

    val value = unresolvedValue.asInstanceOf[T]

    // If nullability is false and value is null, no-op
    if (!isNullable && value == null) {
      return
    }

    // Update `hasNull` flag, if required
    if (!hasNull && value == null) {
      hasNull = true
    }

    if (isCountEnabled) {
      count += 1
    }

    // Min/max statistics are kept only for non-null values
    if (isMinMaxEnabled && value != null) {
      if (min == null || !lt(min, value)) {
        min = value
      }

      if (max == null || lt(max, value)) {
        max = value
      }
    }

    if (isSetEnabled) {
      set.add(value)
    }
  }

  /** Get count, if mode is enabled, otherwise None */
  def getCount(): Option[Long] = {
    if (isCountEnabled) Some(count) else None
  }

  /** Get min/max, internal operation to write min/max */
  def getMinMax(): Option[(T, T)] = {
    if (isMinMaxEnabled) Some((min, max)) else None
  }

  /** Get set, internal operation to write set */
  def getSet(): Option[JHashSet[T]] = {
    if (isSetEnabled) Some(set) else None
  }

  /**
   * Generic method to check boundaries for min/max statistics.
   * Function parameters are min, value, max.
   */
  private def boundaryQuery(value: Any)(func: (T, T, T) => Boolean): Option[Boolean] = {
    if (isMinMaxEnabled) {
      val updatedValue = value.asInstanceOf[T]
      // null value is always out of range and does not confirm to predicate, false is returned
      if (value != null) Some(func(min, updatedValue, max)) else Some(false)
    } else {
      None
    }
  }

  /** Check if value is in min-max range, if mode is enabled, otherwise None */
  def containsInRange(value: Any): Option[Boolean] = {
    boundaryQuery(value) { (min, v, max) => !lt(v, min) && !lt(max, v) }
  }

  /** Check if value is greater than max */
  def lessThanMax(value: Any): Option[Boolean] = {
    boundaryQuery(value) { (min, v, max) => lt(v, max) }
  }

  /** Check if value is greater than or equal to max */
  def lessOrEqualMax(value: Any): Option[Boolean] = {
    boundaryQuery(value) { (min, v, max) => !lt(max, v) }
  }

  /** Check if value is less than min */
  def greaterThanMin(value: Any): Option[Boolean] = {
    boundaryQuery(value) { (min, v, max) => lt(min, v) }
  }

  /** Check if value is less than or equal to min */
  def greaterOrEqualMin(value: Any): Option[Boolean] = {
    boundaryQuery(value) { (min, v, max) => !lt(v, min) }
  }

  /** Check if value is in set, if mode is enabled, otherwise None */
  def containsInSet(value: Any): Option[Boolean] = {
    if (isSetEnabled) Some(set.contains(value)) else None
  }

  /** Update nullability of the attribute */
  def setNull(isNull: Boolean): Unit = {
    hasNull = isNull
  }

  /** Update count with value */
  private[index] def setCount(value: Long): Unit = {
    require(isCountEnabled, s"Count mode is disabled, bit flags: $flags")
    count = value
  }

  /** Update min/max directly with values, lazily update `hasNull` */
  private[index] def setMinMax(minValue: T, maxValue: T): Unit = {
    require(isMinMaxEnabled, s"Min-max mode is disabled, bit flags: $flags")
    setNull(hasNull || minValue == null || maxValue == null)
    min = minValue
    max = maxValue
  }

  /** Update set directly with value, lazily update `hasNull` */
  private[index] def setSet(setValue: JHashSet[T]): Unit = {
    require(isSetEnabled, s"Set mode is disabled, bit flags: $flags")
    setNull(hasNull || setValue == null || setValue.contains(null))
    set = setValue
  }

  /** Internal method to add arbitrary statistics for a specific type */
  private[index] def setStatistics(statsType: Int, iter: Iterator[_]): Unit = {
    if (iter.hasNext) {
      if (statsType == StatisticsUtils.TYPE_COUNT) {
        val count = iter.next.asInstanceOf[Long]
        setCount(count)
      } else if (statsType == StatisticsUtils.TYPE_MINMAX) {
        // This will infer type from runtime classtag of attribute
        val min = iter.next.asInstanceOf[T]
        val max = iter.next.asInstanceOf[T]
        setMinMax(min, max)
      } else if (statsType == StatisticsUtils.TYPE_SET) {
        val internalSet = new JHashSet[T]()
        while (iter.hasNext) {
          internalSet.add(iter.next.asInstanceOf[T])
        }
        setSet(internalSet)
      } else {
        sys.error(s"Unknown statistics type $statsType")
      }
    }
  }

  /** Get actual generic runtime class */
  def getClassTag(): Class[_] = clazz

  /**
   * Whether or not attribute has null values.
   * It checks initial state of min, max and set, and tracks values being added, thus overall
   * nullability is cumulative effect of states.
   */
  def containsNull(): Boolean = hasNull || (isSetEnabled && (set == null || set.contains(null))) ||
    (isMinMaxEnabled && (min == null || max == null))

  override def equals(other: Any): Boolean = other match {
    case castMatch: Attribute[T] =>
      name == castMatch.name &&
      flags == castMatch.flags &&
      containsNull() == castMatch.containsNull() &&
      getCount() == castMatch.getCount() &&
      getMinMax() == castMatch.getMinMax() &&
      getSet() == castMatch.getSet()
    case nonMatch => false
  }

  override def toString(): String = {
    s"${getClass().getCanonicalName}[name: $name, bit flags: $flags, tag: $tag]"
  }
}

object Attribute {
  def apply[T](name: String, flags: Int)(implicit tag: ClassTag[T]): Attribute[T] = {
    apply(name, flags.toByte, tag.runtimeClass.asInstanceOf[Class[T]])
  }

  def apply[T: ClassTag](name: String, flags: Int, clazz: Class[T]): Attribute[T] = {
    val byteFlags = flags.toByte
    if (clazz == classOf[Byte]) {
      new Attribute[Byte](name, _ < _, byteFlags, false).asInstanceOf[Attribute[T]]
    } else if (clazz == classOf[Short]) {
      new Attribute[Short](name, _ < _, byteFlags, false).asInstanceOf[Attribute[T]]
    } else if (clazz == classOf[Int]) {
      new Attribute[Int](name, _ < _, byteFlags, false).asInstanceOf[Attribute[T]]
    } else if (clazz == classOf[Long]) {
      new Attribute[Long](name, _ < _, byteFlags, false).asInstanceOf[Attribute[T]]
    } else if (clazz == classOf[String]) {
      new Attribute[String](name, _ < _, byteFlags, true).asInstanceOf[Attribute[T]]
    } else {
      sys.error(s"Unsupported attribute class $clazz")
    }
  }
}
