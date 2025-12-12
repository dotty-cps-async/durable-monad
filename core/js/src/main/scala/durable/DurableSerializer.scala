package example.durable

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/**
 * Typeclass for serializing/deserializing values in durable context.
 * Values must be convertible to/from js.Any for JSON persistence.
 */
trait DurableSerializer[A]:
  def serialize(a: A): js.Any
  def deserialize(v: js.Any): A

object DurableSerializer:
  /** Create a serializer from functions */
  def apply[A](ser: A => js.Any, deser: js.Any => A): DurableSerializer[A] =
    new DurableSerializer[A]:
      def serialize(a: A): js.Any = ser(a)
      def deserialize(v: js.Any): A = deser(v)

  // Primitive serializers
  given DurableSerializer[Int] = DurableSerializer(
    a => a.asInstanceOf[js.Any],
    v => v.asInstanceOf[Int]
  )

  given DurableSerializer[Long] = DurableSerializer(
    a => a.toDouble.asInstanceOf[js.Any],  // JS numbers are doubles
    v => v.asInstanceOf[Double].toLong
  )

  given DurableSerializer[Double] = DurableSerializer(
    a => a.asInstanceOf[js.Any],
    v => v.asInstanceOf[Double]
  )

  given DurableSerializer[Float] = DurableSerializer(
    a => a.toDouble.asInstanceOf[js.Any],
    v => v.asInstanceOf[Double].toFloat
  )

  given DurableSerializer[Boolean] = DurableSerializer(
    a => a.asInstanceOf[js.Any],
    v => v.asInstanceOf[Boolean]
  )

  given DurableSerializer[String] = DurableSerializer(
    a => a.asInstanceOf[js.Any],
    v => v.asInstanceOf[String]
  )

  given DurableSerializer[Unit] = DurableSerializer(
    _ => ().asInstanceOf[js.Any],
    _ => ()
  )

  // js.Dynamic passes through directly
  given DurableSerializer[js.Dynamic] = DurableSerializer(
    a => a.asInstanceOf[js.Any],
    v => v.asInstanceOf[js.Dynamic]
  )

  given DurableSerializer[js.Any] = DurableSerializer(
    a => a,
    v => v
  )

  // Option
  given [A](using ser: DurableSerializer[A]): DurableSerializer[Option[A]] = DurableSerializer(
    {
      case Some(a) => js.Dynamic.literal(defined = true, value = ser.serialize(a))
      case None => js.Dynamic.literal(defined = false)
    },
    v => {
      val obj = v.asInstanceOf[js.Dynamic]
      if obj.defined.asInstanceOf[Boolean] then
        Some(ser.deserialize(obj.value.asInstanceOf[js.Any]))
      else
        None
    }
  )

  // Tuples
  given [A, B](using serA: DurableSerializer[A], serB: DurableSerializer[B]): DurableSerializer[(A, B)] =
    DurableSerializer(
      { case (a, b) => js.Array(serA.serialize(a), serB.serialize(b)) },
      v => {
        val arr = v.asInstanceOf[js.Array[js.Any]]
        (serA.deserialize(arr(0)), serB.deserialize(arr(1)))
      }
    )

  given [A, B, C](using
    serA: DurableSerializer[A],
    serB: DurableSerializer[B],
    serC: DurableSerializer[C]
  ): DurableSerializer[(A, B, C)] =
    DurableSerializer(
      { case (a, b, c) => js.Array(serA.serialize(a), serB.serialize(b), serC.serialize(c)) },
      v => {
        val arr = v.asInstanceOf[js.Array[js.Any]]
        (serA.deserialize(arr(0)), serB.deserialize(arr(1)), serC.deserialize(arr(2)))
      }
    )

  // List
  given [A](using ser: DurableSerializer[A]): DurableSerializer[List[A]] = DurableSerializer(
    list => js.Array(list.map(ser.serialize)*),
    v => v.asInstanceOf[js.Array[js.Any]].toList.map(ser.deserialize)
  )

  // Seq (uses List)
  given [A](using ser: DurableSerializer[A]): DurableSerializer[Seq[A]] = DurableSerializer(
    seq => js.Array(seq.map(ser.serialize).toSeq*),
    v => v.asInstanceOf[js.Array[js.Any]].toSeq.map(ser.deserialize)
  )

  // js.Array
  given [A](using ser: DurableSerializer[A]): DurableSerializer[js.Array[A]] = DurableSerializer(
    arr => js.Array(arr.toSeq.map(a => ser.serialize(a))*),
    v => {
      val arr = v.asInstanceOf[js.Array[js.Any]]
      js.Array(arr.toSeq.map(ser.deserialize)*)
    }
  )

  // Map[String, A]
  given [A](using ser: DurableSerializer[A]): DurableSerializer[Map[String, A]] = DurableSerializer(
    map => js.Dictionary(map.view.mapValues(ser.serialize).toSeq*),
    v => v.asInstanceOf[js.Dictionary[js.Any]].toMap.view.mapValues(ser.deserialize).toMap
  )

  // Either
  given [A, B](using serA: DurableSerializer[A], serB: DurableSerializer[B]): DurableSerializer[Either[A, B]] =
    DurableSerializer(
      {
        case Left(a) => js.Dynamic.literal(isRight = false, value = serA.serialize(a))
        case Right(b) => js.Dynamic.literal(isRight = true, value = serB.serialize(b))
      },
      v => {
        val obj = v.asInstanceOf[js.Dynamic]
        if obj.isRight.asInstanceOf[Boolean] then
          Right(serB.deserialize(obj.value.asInstanceOf[js.Any]))
        else
          Left(serA.deserialize(obj.value.asInstanceOf[js.Any]))
      }
    )
