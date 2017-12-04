package group23;

import java.io.Serializable;

public class Pair<A, B> implements Serializable {
	private A first;
	private B second;

	public Pair(A first, B second) {
		this.first = first;
		this.second = second;
	}

	public A getFirst() {
		return first;
	}

	public B getSecond() {
		return second;
	}

	public void setFirst(A first) {
		this.first = first;
	}

	public void setSecond(B second) {
		this.second = second;
	}

	@Override
	public String toString() {
		return "group23.Pair(" + this.getFirst() + ", " + this.getSecond() + ")";
	}

	public boolean equals(Pair other) {
		return other != null && this.getFirst() == other.getFirst() && this.getSecond() == other.getSecond();
	}
}

