package scalacv

import scalacv.graphs.*
import scalacv.vision.*

/** Static hand-gesture recognition from synthetic Hand21 poses — no model, pure geometry. */
class GestureTest extends munit.FunSuite:

  /** Builds a Hand21 pose. `fingers` = (thumb, index, middle, ring, pinky) extended flags. An extended
    * finger's tip sits far from the wrist (up the frame); a curled one's tip sits close.
    */
  private def hand(fingers: (Boolean, Boolean, Boolean, Boolean, Boolean)): Pose =
    val (t, i, m, r, p) = fingers
    val wrist = Point(50, 100)
    val pts = Array.fill(21)(wrist) // everything defaults to the wrist
    // For each finger, set the middle joint (tip-2) and the tip (the two points recognize() compares).
    def finger(tip: Int, extended: Boolean, x: Double): Unit =
      pts(tip - 2) = Point(x, if extended then 60 else 55) // joint
      pts(tip) = Point(x, if extended then 20 else 82) // tip: far when extended, near when curled
    finger(4, t, 30) // thumb
    finger(8, i, 42) // index
    finger(12, m, 50) // middle
    finger(16, r, 58) // ring
    finger(20, p, 66) // pinky
    val names = PoseTopology.Hand21.names
    Pose(names.indices.map(idx => Keypoint(names(idx), pts(idx), 0.9f)).toSeq, PoseTopology.Hand21)

  test("a closed hand is a fist"):
    assertEquals(GestureRecognizer.recognize(hand((false, false, false, false, false))), HandGesture.Fist)

  test("all fingers out is an open palm"):
    assertEquals(GestureRecognizer.recognize(hand((true, true, true, true, true))), HandGesture.OpenPalm)

  test("thumb only is thumbs up"):
    assertEquals(GestureRecognizer.recognize(hand((true, false, false, false, false))), HandGesture.ThumbsUp)

  test("index only is pointing"):
    assertEquals(GestureRecognizer.recognize(hand((false, true, false, false, false))), HandGesture.Pointing)

  test("index and middle is victory"):
    assertEquals(GestureRecognizer.recognize(hand((false, true, true, false, false))), HandGesture.Victory)

  test("a non-Hand21 pose is rejected"):
    val body = Pose(Seq(Keypoint("nose", Point(0, 0), 0.9f)), PoseTopology(Seq("nose"), Seq.empty))
    intercept[IllegalArgumentException](GestureRecognizer.recognize(body))

  test("a finger whose middle joint is unscored is not extended, however confident the tip"):
    // Geometrically the index is out (tip far above the wrist, joint near it), and the tip's score
    // clears the gate — but the middle joint the comparison runs against was never really seen. Gating
    // only the tip would call this finger extended (Pointing); it must read as not extended (Fist).
    val base = hand((false, true, false, false, false))
    val unscoredJoint = base.keypoints.updated(6, base.keypoints(6).copy(score = 0.1f))
    val pose = Pose(unscoredJoint, base.topology)
    assertEquals(GestureRecognizer.recognize(pose, minScore = 0.3f), HandGesture.Fist)
