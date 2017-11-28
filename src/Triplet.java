import java.io.Serializable;

public class Triplet<A, B, C> implements Serializable {
	private A first;
	private B second;
	private C third;

	public Triplet(A first, B second, C third) {
		this.first = first;
		this.second = second;
		this.third = third;
	}

	public A getFirst() {
		return first;
	}

	public B getSecond() {
		return second;
	}

	public C getThird() {
		return third;
	}

	public void setFirst(A first) {
		this.first = first;
	}

	public void setSecond(B second) {
		this.second = second;
	}

	public void setThird(C third) {
		this.third = third;
	}

	@Override
	public String toString() {
		return "Pair(" + this.getFirst() + ", " + this.getSecond() + "," + this.getThird() + ")";
	}

	public boolean equals(Triplet other) {
		return other != null && this.getFirst() == other.getFirst() && this.getSecond() == other.getSecond() && this.getThird() == other.getThird();
	}
}

