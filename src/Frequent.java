import misc.Pair;
import negotiator.AgentID;
import negotiator.Bid;
import negotiator.actions.Accept;
import negotiator.actions.Action;
import negotiator.actions.Offer;
import negotiator.issue.*;
import negotiator.parties.AbstractNegotiationParty;
import negotiator.parties.NegotiationInfo;
import negotiator.utility.AdditiveUtilitySpace;
import negotiator.utility.UtilitySpace;

import java.util.*;

public class Frequent extends AbstractNegotiationParty {
	private String description = "Frequent";
	private int round = 0;
	private double stubbornness = 5_000;
	private Bid maxbid;
	private Map<Integer, Double> weights = new HashMap<>();

	private Deque<Pair<AgentID, Offer>> history = new LimitedQueue<>(250);
	private Map<AgentID, Offer> agents = new HashMap<>();
	private Map<Integer, Map<String, Integer>> frequencies = new HashMap<>();

	/**
	 * Lerp between two values a and b using t
	 */
	private int lerp(int a, int b, float t) {
		return (int) (a + t * (b - a));
	}

	private int lerp(int a, int b, double t) {
		return (int) (a + t * (b - a));
	}

	private float lerp(float a, float b, float t) {
		return a + t * (b - a);
	}

	private double lerp(double a, double b, double t) {
		return a + t * (b - a);
	}

	/**
	 * Clamp value x between min and max
	 */
	private int clamp(int x, int min, int max) {
		return x < max ? (x > min ? x : min) : max;
	}

	private float clamp(float x, float min, float max) {
		return x < max ? (x > min ? x : min) : max;
	}

	private double clamp(double x, double min, double max) {
		return x < max ? (x > min ? x : min) : max;
	}

	private float clamp01(int x) {
		return clamp(x, 0, 1);
	}

	private float clamp01(float x) {
		return clamp(x, 0, 1);
	}

	private double clamp01(double x) {
		return clamp(x, 0, 1);
	}

	/**
	 * How much are we willing to conceed at time t?
	 */
	private double conceed(double t) {
		return clamp01(-(Math.pow(stubbornness, clamp01(t)) / stubbornness) + 1);
	}

	@Override
	public void init(NegotiationInfo info) {
		super.init(info);
	}

	/**
	 * When this function is called, it is expected that the Party chooses one of the actions from the possible
	 * action list and returns an instance of the chosen action.
	 */
	@Override
	public Action chooseAction(List<Class<? extends Action>> list) {
		System.out.println(getDescription() + ": ChooseAction(" + list + ")");

		if (maxbid == null) {
			maxbid = this.getMaxUtilityBid();
			agents.put(this.getPartyId(), new Offer(this.getPartyId(), maxbid));

			UtilitySpace space = this.getUtilitySpace();
			// Default weights of value = 1
			int numberOfIssues = space.getDomain().getIssues().size();

			// Assign weights if we are additive
			if(space instanceof AdditiveUtilitySpace) {
				AdditiveUtilitySpace additiveSpace = (AdditiveUtilitySpace) space;

				// Find the weights using the additive space
				Map<Integer, Value> map = new HashMap<>(maxbid.getValues());
				for (Integer id : map.keySet()) weights.put(id, additiveSpace.getWeight(id) * numberOfIssues);
			} else {
				// Set weights to 1 as a fallback
				Map<Integer, Value> map = new HashMap<>(maxbid.getValues());
				for (Integer id : map.keySet()) weights.put(id, 1.0);
			}
		}

		// According to Stacked Alternating Offers Protocol list includes
		// Accept, Offer and EndNegotiation actions only.
		double time = getTimeLine().getTime(); // Gets the time, running from t = 0 (start) to t = 1 (deadline).
		double willingness = conceed(time);
		// The time is normalized, so agents need not be
		// concerned with the actual internal clock.

		// Increase our round timer.
		++round;

		// We need some time to understand our opponents
		// Means we have enough data and we don't (really) need to worry about nulls
		if (round <= 2) return new Offer(this.getPartyId(), maxbid);

		// Last bid
		Bid last = history.peekLast().getSecond().getBid();

		// Find the average
		Map<Integer, Value> proposal = new HashMap<>(maxbid.getValues());

		int discreteConcessions = (int) Math.floor(proposal.size() * (1 - willingness));

		for (Map.Entry<Integer, Value> entry : proposal.entrySet()) {
			int id = entry.getKey();
			Value value = entry.getValue();

			if (value instanceof ValueDiscrete) {
//				System.err.println("ValueDiscrete is not implemented! Defaulting to max bid.");

//				// Some randomness to spice things up, weighted of course
//				// TODO actually test this code!
//				if(discreteConcessions > 0 && Math.random() > 0.5 * weights.get(id)) {
//					proposal.put(id, last.getValue(id));
//					discreteConcessions -= 1;
//				}


			} else if (value instanceof ValueInteger) {
				int sum = 0;
				int count = 0;

				for (Map.Entry<AgentID, Offer> agent : agents.entrySet()) {
					sum += ((ValueInteger) agent.getValue().getBid().getValue(id)).getValue();
					++count;
				}

				int bestValue = sum / count; // Start with the average
				int minDifference = Math.abs(bestValue - ((ValueInteger) maxbid.getValue(id)).getValue());

				for (Map.Entry<AgentID, Offer> agent : agents.entrySet()) {
					if(agent.getKey() == this.getPartyId()) continue;

					int difference = Math.abs(((ValueInteger) agent.getValue().getBid().getValue(id)).getValue() - ((ValueInteger) maxbid.getValue(id)).getValue());
					if (difference < minDifference) {
						minDifference = difference;
						bestValue = ((ValueInteger) agent.getValue().getBid().getValue(id)).getValue();
					}
				}

				proposal.put(id, new ValueInteger(lerp(bestValue, ((ValueInteger) maxbid.getValue(id)).getValue(), Math.pow(willingness, weights.get(id)))));
			} else if (value instanceof ValueReal) {
				double sum = 0;
				int count = 0;

				for (Map.Entry<AgentID, Offer> agent : agents.entrySet()) {
					sum += ((ValueReal) agent.getValue().getBid().getValue(id)).getValue();
					++count;
				}

				double bestValue = sum / count; // Start with the average
				double minDifference = Math.abs(bestValue - ((ValueReal) maxbid.getValue(id)).getValue());

				for (Map.Entry<AgentID, Offer> agent : agents.entrySet()) {
					if(agent.getKey() == this.getPartyId()) continue;

					double difference = Math.abs(((ValueReal) agent.getValue().getBid().getValue(id)).getValue() - ((ValueReal) maxbid.getValue(id)).getValue());
					if (difference < minDifference) {
						minDifference = difference;
						bestValue = ((ValueInteger) agent.getValue().getBid().getValue(id)).getValue();
					}
				}

				proposal.put(id, new ValueReal(lerp(bestValue, ((ValueReal) maxbid.getValue(id)).getValue(), Math.pow(willingness, weights.get(id)))));
			} else {
				throw new UnsupportedOperationException("Unexpected value type!");
			}
		}

		// Is the offer good enough?
		Bid bid = new Bid(this.getUtilitySpace().getDomain(), new HashMap<>(proposal));
		if (this.getUtilitySpace().getUtility(last) >= this.getUtilitySpace().getUtility(bid)) {
			System.out.println(getDescription() + ": Accepting offer " + this.getUtilitySpace().getUtility(last) + " " + last);
			return new Accept(this.getPartyId(), last);
		} else {
			// Offer is no good, propose our own
			System.out.println(getDescription() + ": Proposing offer " + this.getUtilitySpace().getUtility(bid) + " " + bid);
			return new Offer(this.getPartyId(), bid);
		}
	}

	/**
	 * This method is called to inform the party that another NegotiationParty chose an Action.
	 */
	@Override
	public void receiveMessage(AgentID sender, Action act) {
		super.receiveMessage(sender, act);

		System.out.println(getDescription() + ": receiveMessage(" + sender + "," + act + ")");

		if (act instanceof Offer) { // sender is making an offer
			Offer offer = (Offer) act;

			// storing last received offer
			history.add(new Pair<>(sender, offer));
			if (!agents.containsKey(sender)) agents.put(sender, offer);
		}
	}

	/**
	 * A human-readable description for this party.
	 */
	@Override
	public String getDescription() {
		return description;
	}

	private Bid getMaxUtilityBid() {
		try {
			return this.getUtilitySpace().getMaxUtilityBid();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
}
