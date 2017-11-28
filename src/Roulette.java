import negotiator.AgentID;
import negotiator.Bid;
import negotiator.actions.Accept;
import negotiator.actions.Action;
import negotiator.actions.EndNegotiation;
import negotiator.actions.Offer;
import negotiator.issue.*;
import negotiator.parties.AbstractNegotiationParty;
import negotiator.parties.NegotiationInfo;
import negotiator.utility.AdditiveUtilitySpace;
import negotiator.utility.EvaluatorDiscrete;
import negotiator.utility.UtilitySpace;

import java.sql.Timestamp;
import java.util.*;

public class Roulette extends AbstractNegotiationParty {
	private String description = "A Game of Chance";
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

	private void log(Object... objects) {
		System.out.println(new Timestamp(System.currentTimeMillis()) + " " + getDescription() + ": " + objects);
	}
	private void warn(Object... objects) {
		System.err.println(new Timestamp(System.currentTimeMillis()) + " " + getDescription() + ": " + objects);
	}

	private boolean within(int value, int min, int max) {return value >= min && value <= max;}
	private boolean within(float value, float min, float max) {return value >= min && value <= max;}
	private boolean within(double value, double min, double max) {return value >= min && value <= max;}

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
		log("ChooseAction(" + list + ")");

		if (maxbid == null) {
			maxbid = this.getMaxUtilityBid();
			agents.put(this.getPartyId(), new Offer(this.getPartyId(), maxbid));

			UtilitySpace space = this.getUtilitySpace();
			// Default weights of value = 1
			int numberOfIssues = maxbid.getIssues().size();

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

			this.receiveMessage(this.getPartyId(), new Offer(this.getPartyId(), new Bid(this.getUtilitySpace().getDomain(), maxbid.getValues())));
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

		// Make a proposal
		Map<Integer, Value> proposal = new HashMap<>(maxbid.getValues());
		// Roulette
		Pair<Double, List<Triplet<Double, Double, List<Pair<Double, String>>>>> roulette = new Pair<>(0.0, new ArrayList<>());

		proposal.forEach((Integer id, Value value) -> {
			if (value instanceof ValueDiscrete) {
				// Only makes sense if we have an additive space
				if(this.getUtilitySpace() instanceof AdditiveUtilitySpace) {
					List<Pair<Double, String>> sublist = new ArrayList<>();
					double max = 0, total = 0;

					// iterate over values, adding to pair
					AdditiveUtilitySpace additiveSpace = (AdditiveUtilitySpace) this.getUtilitySpace();

					for (ValueDiscrete valueDiscrete : ((IssueDiscrete) additiveSpace.getDomain().getIssues().get(id - 1)).getValues()) {
						// score each choice
						double evaluation = 0.5;
						try {
							evaluation = ((EvaluatorDiscrete) additiveSpace.getEvaluator(id)).getEvaluation(valueDiscrete);
						} catch (Exception e) {
							warn("Failed to getEvaluation(" + valueDiscrete + ")");
						}
						double frequency = frequencies.get(id).containsKey(valueDiscrete.getValue()) ? frequencies.get(id).get(valueDiscrete.getValue()) / frequencies.get(id).get("__total__") : (1.0 / maxbid.getIssues().size());
						double score = evaluation * frequency * weights.get(id);
						max = Math.max(max, score);
						total += score;
						sublist.add(new Pair<>(score, valueDiscrete.getValue()));
					}

					roulette.setFirst(roulette.getFirst() + total);
					roulette.getSecond().add(new Triplet<>(max, total, sublist));
				}
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
				long count = 0;

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
		});

		// Spin the wheel, if additive
		if (this.getUtilitySpace() instanceof AdditiveUtilitySpace) {
			// Loop until within range, loop with an upper limit.
			for(int c = 0; c < 10 * maxbid.getIssues().size() && !within(this.getUtility(new Bid(this.getUtilitySpace().getDomain(), (HashMap) proposal)), willingness - 0.1, willingness + 0.1); c++) {
				double outervalue = Math.random() * roulette.getFirst();

				for (int i = 0; i < roulette.getSecond().size(); i++) {
					Triplet<Double, Double, List<Pair<Double, String>>> issue = roulette.getSecond().get(i);
					outervalue -= issue.getFirst();

					if (outervalue <= 0) {
						// We have found our issue
						double innervalue = Math.random() * issue.getSecond();

						for (int j = 0; j < issue.getThird().size(); j++) {
							Pair<Double, String> choice = issue.getThird().get(j);
							innervalue -= choice.getFirst();

							// We have found our choice
							if (innervalue <= 0) {
								proposal.put(i, new ValueDiscrete(choice.getSecond()));
								break;
							}
						}

						break;
					}
				}
			}
		}

		// Is the offer good enough?
		Bid bid = new Bid(this.getUtilitySpace().getDomain(), (HashMap) proposal);
		if (this.getUtilitySpace().getUtility(last) >= this.getUtilitySpace().getUtility(bid)) {
			log("Accepting offer " + this.getUtilitySpace().getUtility(last) + " " + last);
			Accept accept = new Accept(this.getPartyId(), last);
			receiveMessage(this.getPartyId(), accept);
			return accept;
		} else {
			// Offer is no good, propose our own
			log("Proposing offer " + this.getUtilitySpace().getUtility(bid) + " " + bid);
			Offer offer = new Offer(this.getPartyId(), bid);
			receiveMessage(this.getPartyId(), offer);
			return offer;
		}
	}

	/**
	 * This method is called to inform the party that another NegotiationParty chose an Action.
	 */
	@Override
	public void receiveMessage(AgentID sender, Action act) {
		super.receiveMessage(sender, act);

		log("receiveMessage(" + sender + "," + act + ")");

		if (act instanceof Offer) { // sender is making an offer
			Offer offer = (Offer) act;

			// storing last received offer
			history.add(new Pair<>(sender, offer));
			if (!agents.containsKey(sender)) agents.put(sender, offer);

			offer.getBid().getValues().forEach((Integer id, Value value) -> {
				// We only really care about discrete values
				if(value instanceof ValueDiscrete) {
					if(!frequencies.containsKey(id)) {
						frequencies.put(id, new HashMap<>());
						frequencies.get(id).put("__total__", 0);
					}

					String string = ((ValueDiscrete) value).getValue();
					if(!frequencies.get(id).containsKey(string)) {
						frequencies.get(id).put(string, 1);
					} else {
						frequencies.get(id).put(string, frequencies.get(id).get(string) + 1);
					}
					frequencies.get(id).put("__total__", frequencies.get(id).get("__total__") + 1);
				}
			});
		} else if (act instanceof Accept) {
			log("Awesome!");
		} else if (act instanceof EndNegotiation) {
			warn("BOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO!");
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
			warn("Failed to get maxUtilityBid()!");
			return this.generateRandomBid();
		}
	}
}
