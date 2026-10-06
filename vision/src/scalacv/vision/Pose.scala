package scalacv.vision

import scalacv.*

/* The pose data model: keypoints, topologies and the immutable [[Pose]] they make up, plus the skeleton
 * drawing overlay. The tensor decode that produces a Pose from a network lives in PoseEstimator.scala, and
 * head orientation via solvePnP in HeadPose.scala.
 */

/** One named landmark of a [[Pose]] — a point in image pixels and the model's confidence in it. */
final case class Keypoint(name: String, point: Point, score: Float)

/** A keypoint naming and connectivity scheme — the "which landmark is which, and which bones connect them"
  * that a pose model implies but does not carry.
  *
  * @param names
  *   the keypoint names, in the model's output order.
  * @param edges
  *   index pairs that form the skeleton's bones (for drawing).
  */
final case class PoseTopology(names: Seq[String], edges: Seq[(Int, Int)]):
  require(
    edges.forall((a, b) => a >= 0 && a < names.size && b >= 0 && b < names.size),
    "every edge must reference valid keypoint indices"
  )

  /** The number of keypoints this topology expects. */
  def size: Int = names.size

object PoseTopology:

  /** The 17-keypoint COCO body layout used by MoveNet and OpenPose(COCO). */
  val CocoBody17: PoseTopology = PoseTopology(
    names = Seq(
      "nose",
      "left_eye",
      "right_eye",
      "left_ear",
      "right_ear",
      "left_shoulder",
      "right_shoulder",
      "left_elbow",
      "right_elbow",
      "left_wrist",
      "right_wrist",
      "left_hip",
      "right_hip",
      "left_knee",
      "right_knee",
      "left_ankle",
      "right_ankle"
    ),
    edges = Seq(
      (0, 1),
      (0, 2),
      (1, 3),
      (2, 4), // head
      (5, 6), // shoulders
      (5, 7),
      (7, 9), // left arm
      (6, 8),
      (8, 10), // right arm
      (5, 11),
      (6, 12),
      (11, 12), // torso
      (11, 13),
      (13, 15), // left leg
      (12, 14),
      (14, 16) // right leg
    )
  )

  /** The 21-landmark hand layout used by MediaPipe Hands: wrist, then thumb→pinky, four points each. */
  val Hand21: PoseTopology =
    val fingers = Seq("thumb", "index", "middle", "ring", "pinky")
    val joints = Seq("cmc", "mcp", "ip", "tip") // thumb naming; the others read mcp/pip/dip/tip but the
    // count is what matters for the skeleton, so a uniform four-per-finger naming keeps it simple.
    val names = "wrist" +: fingers.flatMap(f => joints.map(j => s"${f}_$j"))
    val edges = fingers.zipWithIndex.flatMap: (_, fi) =>
      val base = 1 + fi * 4
      Seq((0, base)) ++ (0 until 3).map(k => (base + k, base + k + 1))
    PoseTopology(names, edges)

/** A detected pose — an ordered set of [[Keypoint]]s for a known [[PoseTopology]], as plain immutable data
  * that stays valid after the frame and the network output are freed.
  */
final case class Pose(keypoints: Seq[Keypoint], topology: PoseTopology):
  // The topology is an index scheme over these keypoints, so the two lengths are one fact, not two.
  // Without this check the mismatch is not caught anywhere — it surfaces later as an
  // IndexOutOfBoundsException from `bones` (whose edge indices are validated against the *topology*) or
  // from `GestureRecognizer.recognize` (which validates the *topology* is Hand21 and then reads 21
  // keypoints). Both would blame the wrong line. `Pose` is public data and the documentation encourages
  // building one by hand from your own model's output, so this is the constructor a mismatch arrives at.
  require(
    keypoints.size == topology.size,
    s"a pose must have one keypoint per topology entry: got ${keypoints.size} keypoints for a " +
      s"${topology.size}-landmark topology"
  )

  /** The keypoint with this name, if the model reported it. */
  def apply(name: String): Option[Keypoint] = keypoints.find(_.name == name)

  /** Keypoints at or above `minScore`. */
  def confident(minScore: Float = 0.3f): Seq[Keypoint] = keypoints.filter(_.score >= minScore)

  /** Mean confidence across all keypoints — a quick "is there a pose here at all" score. */
  def meanScore: Float = if keypoints.isEmpty then 0f else keypoints.map(_.score).sum / keypoints.size

  /** The skeleton's bones as point pairs, keeping only those whose **both** endpoints clear `minScore`. Ready
    * to draw.
    */
  def bones(minScore: Float = 0.3f): Seq[(Point, Point)] =
    topology.edges.collect:
      case (a, b) if keypoints(a).score >= minScore && keypoints(b).score >= minScore =>
        (keypoints(a).point, keypoints(b).point)

/** The pose overlay on [[Image]] — an extension method so it lives beside the pose data model rather than in
  * the image class. `import scalacv.*` gives `image.drawSkeleton(pose)`.
  */
extension (img: Image)

  /** Draws a [[Pose]] skeleton: a line per bone and a dot per confident keypoint. */
  def drawSkeleton(
      pose: Pose,
      minScore: Float = 0.3f,
      color: Scalar = Scalar.Green,
      jointColor: Scalar = Scalar.Red
  ): Image =
    img.paint: m =>
      pose.bones(minScore).foreach((a, b) => m.drawLine(a, b, color, Thickness.Stroke(2)))
      pose.confident(minScore).foreach(kp => m.drawCircle(kp.point, 3, jointColor, Thickness.Filled))
