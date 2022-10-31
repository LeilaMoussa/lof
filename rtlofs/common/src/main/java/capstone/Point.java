package capstone;

import java.util.ArrayList;

public class Point {
    ArrayList<Double> attributes;
    int dim;

    Point(int d, ArrayList<Double> parsed) {
      this.dim = d;
      this.attributes = parsed;
    }

    public Double getAttribute(int index) {
      return index < this.dim ? this.attributes.get(index) : null;
    }

    public Double getDistanceTo(Point other, String distanceMeasure) {
      double distance = 0;
      switch (distanceMeasure) {
        case "EUCLIDEAN":
          for (int i = 0; i < this.dim; i++) {
            distance += Math.pow(this.getAttribute(i) - other.getAttribute(i), 2);
          }
          return Math.sqrt(distance);
        case "MANHATTAN":
          for (int i = 0; i < this.dim; i++) {
            distance += this.getAttribute(i) - other.getAttribute(i);
          }
          return distance;
        default:
          return null;
      }
    }

    @Override
    public boolean equals(Object other) {
      if (other == null || !(other instanceof Point)) return false;
      Point otherPoint = (Point)other;
      boolean mismatch = false;
      for (int i = 0; i < this.dim; i++) {
        mismatch = this.getAttribute(i) != otherPoint.getAttribute(i);
        if (mismatch) break;
      }
      return !mismatch;
    }

    @Override
    public int hashCode() {
      String str = "";
      for (int i = 0; i < this.dim; i++) {
        str += this.getAttribute(i).toString();
      }
      return str.hashCode();
    }

    @Override
    public String toString() {
      String str = "";
      for (int i = 0; i < this.dim; i++) {
        str += this.getAttribute(i) + " ";
      }
      return str;
    }
}
