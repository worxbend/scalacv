package scalacv.vision

import scalacv.*

/** A recognised static hand gesture. */
enum HandGesture:
  case Fist, OpenPalm, ThumbsUp, Pointing, Victory, Unknown

/** Static hand-gesture recognition from a [[PoseTopology.Hand21]] hand pose.
  *
  * This is the rule-based layer on top of hand-landmark estimation: given the 21 landmarks (from a hand model
  * run through [[Dnn]] and decoded with [[PoseEstimator]]), it decides which fingers are extended and names
  * the gesture. No model of its own — pure geometry — so it is deterministic and testable.
  *
  * It is also the entry point for **sign language**: static fingerspelling shapes are exactly this (extend
  * the ruleset, or classify the landmark vector with your own model); dynamic signs add a temporal classifier
  * over a sequence of these poses — see the docs.
  *
  * {{{
  * val gesture = GestureRecognizer.recognize(handPose) // handPose: Pose over PoseTopology.Hand21
  * }}}
  */
object GestureRecognizer:

  /** Names the gesture in `pose`, which must be a 21-landmark hand pose. `minScore` gates which keypoints are
    * trusted; a finger whose tip *or middle joint* is below it counts as not extended.
    */
  def recognize(pose: Pose, minScore: Float = 0.3f): HandGesture =
    require(
      pose.topology.size == PoseTopology.Hand21.size,
      s"gesture recognition needs a Hand21 pose (21 landmarks), got ${pose.topology.size}"
    )
    val kp = pose.keypoints
    val wrist = kp(0).point

    // A finger is extended when its tip sits farther from the wrist than its middle joint — orientation
    // independent, so it holds whichever way the hand is turned. BOTH landmarks must clear `minScore`:
    // comparing against a joint the model could not actually see (score below the gate, point meaningless)
    // would let a confident tip alone read a curled finger as extended.
    def extended(tip: Int, joint: Int): Boolean =
      kp(tip).score >= minScore && kp(joint).score >= minScore &&
        kp(tip).point.distanceTo(wrist) > kp(joint).point.distanceTo(wrist)

    val thumb = extended(4, 2)
    val index = extended(8, 6)
    val middle = extended(12, 10)
    val ring = extended(16, 14)
    val pinky = extended(20, 18)

    (thumb, index, middle, ring, pinky) match
      case (false, false, false, false, false) => HandGesture.Fist
      case (true, false, false, false, false) => HandGesture.ThumbsUp
      case (false, true, false, false, false) => HandGesture.Pointing
      case (false, true, true, false, false) => HandGesture.Victory
      case (t, i, m, r, p) if Seq(t, i, m, r, p).count(identity) >= 4 => HandGesture.OpenPalm
      case _ => HandGesture.Unknown
