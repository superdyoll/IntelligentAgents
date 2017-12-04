package group23;

import negotiator.AgentID;
import negotiator.Bid;
import negotiator.actions.*;
import negotiator.issue.*;
import negotiator.parties.*;
import negotiator.utility.*;

import java.sql.Timestamp;
import java.util.*;

@SuppressWarnings({"SameParameterValue", "WeakerAccess", "unused"})
public class Agent23 extends AbstractNegotiationParty {
	protected static int created = 0;

	/**
	 * Round tracker
	 */
	protected int round = 0;
	/**
	 * Controls how quickly the agent concedes
	 */
	@SuppressWarnings("CanBeFinal")
	protected double stubbornness = 13_000;
	protected double minimumUtility = 0.4;
	/**
	 * Make the agent random
	 */
	protected int randomFrequency = 50;
	protected int randomSpike = (int) Math.round(Math.random() * randomFrequency);
	/**
	 * How we want to bias our wheel. 1 = our most important issue never gets changed, > 1 = our most important issue gets changed using the bias
	 */
	@SuppressWarnings("CanBeFinal")
	protected double issueBias = 1.15; // Don't go below 1!
	/**
	 * Max bid possible for the agent
	 */
	protected Bid maxBid;
	/**
	 * Weighting of different issues
	 */
	protected final Map<Integer, Double> weights = new HashMap<>();
	/**
	 * History of bids made
	 */
	protected final Deque<Pair<AgentID, Offer>> history = new LimitedQueue<>(250);
	/**
	 * The other agents in the negotiation
	 */
	protected final Map<AgentID, Offer> agents = new HashMap<>();
	/**
	 * Frequency of previous values bids
	 */
	protected final Map<Integer, Map<String, Integer>> frequencies = new HashMap<>();

    //<editor-fold desc="Lerps">
    /**
	 * Lerp between two values a and b using t
	 */
	protected static int lerp(int a, int b, float t) {
		return (int) (a + t * (b - a));
	}
	protected static int lerp(int a, int b, double t) {
		return (int) (a + t * (b - a));
	}
	protected static float lerp(float a, float b, float t) {
		return a + t * (b - a);
	}
	protected static double lerp(double a, double b, double t) {
		return a + t * (b - a);
	}
    //</editor-fold>

    //<editor-fold desc="Clamps">
    /**
	 * Clamp value x between min and max
	 */
	protected static int clamp(int x, int min, int max) {
		return x < max ? (x > min ? x : min) : max;
	}
	protected static float clamp(float x, float min, float max) {
		return x < max ? (x > min ? x : min) : max;
	}
	protected static double clamp(double x, double min, double max) {
		return x < max ? (x > min ? x : min) : max;
	}
	protected static float clamp01(int x) {
		return clamp(x, 0, 1);
	}
	protected static float clamp01(float x) {
		return clamp(x, 0, 1);
	}
	protected static double clamp01(double x) {
		return clamp(x, 0, 1);
	}
    //</editor-fold>

	/**
	 * Log formatted messages
	 */
	protected void log(Object... objects) {
		StringBuilder builder = new StringBuilder();
		builder.append(new Timestamp(System.currentTimeMillis())).append(" ").append(getDescription()).append(": ");
		for (Object object : objects) builder.append(object);
		System.out.println(builder.toString());
	}

	/**
	 * Warn formatted messages
	 */
	protected void warn(Object... objects) {
		StringBuilder builder = new StringBuilder();
		builder.append(new Timestamp(System.currentTimeMillis())).append(" ").append(getDescription()).append(": ");
		for (Object object : objects) builder.append(object);
		System.err.println(builder.toString());
	}

    //<editor-fold desc="Withins">
    /**
	 * Check if value is within range
	 */
	protected static boolean within(int value, int min, int max) {
		return value >= min && value <= max;
	}
	protected static boolean within(float value, float min, float max) {
		return value >= min && value <= max;
	}
	protected static boolean within(double value, double min, double max) {
		return value >= min && value <= max;
	}
    //</editor-fold>

	/**
	 * How much are we willing to concede at time t?
	 */
	protected double concede(double t) {
		double randomAmount = 0;
		--randomSpike;
		if (randomSpike <= 0) {
			randomAmount = Math.random() * 0.25;
			randomSpike = (int) Math.round(Math.random() * randomFrequency);
		}

		return Math.max(minimumUtility, clamp01(-(Math.pow(stubbornness, clamp01(t)) / stubbornness) + 0.90 + Math.random() * 0.1 + randomAmount));
	}

	public Agent23() {
		// Count number of instances
		++Agent23.created;
	}

	@Override
	public void init(NegotiationInfo info) {
		super.init(info);
		log("Initialised");
	}

	/**
	 * When this function is called, it is expected that the Party chooses one of the actions from the possible
	 * action list and returns an instance of the chosen action.
	 */
	@Override
	public Action chooseAction(List<Class<? extends Action>> list) {
		try {
			log("ChooseAction(" + list + ")");

			// Check if the max  bid has been set
			if (maxBid == null) {
				// Set the max bid
				maxBid = this.getMaxUtilityBid();

				// Add ourselves to the agents with our preference
				agents.put(this.getPartyId(), new Offer(this.getPartyId(), maxBid));

				// Get the utility space
				UtilitySpace space = this.getUtilitySpace();

				// Default weights of value = 1
				int numberOfIssues = maxBid.getIssues().size();

				// Assign weights if we are additive
				if (space instanceof AdditiveUtilitySpace) {
					AdditiveUtilitySpace additiveSpace = (AdditiveUtilitySpace) space;

					// Get the weighting of the issues
					maxBid.getValues().forEach((Integer id, Value value) -> weights.put(id, additiveSpace.getWeight(id) * numberOfIssues));
				} else {
					// Set weights to 1 as a fallback
					Map<Integer, Value> map = new HashMap<>(maxBid.getValues());
					map.keySet().forEach((Integer id) -> weights.put(id, 1.0));
				}

				// Preload frequencies, make it much better as it can consider more options
				maxBid.getValues().forEach((Integer id, Value value) -> {
					if(value instanceof ValueDiscrete) {
						IssueDiscrete issueDiscrete = (IssueDiscrete) this.getUtilitySpace().getDomain().getIssues().get(id - 1);

						if (!frequencies.containsKey(id)) {
							frequencies.put(id, new HashMap<>());
							frequencies.get(id).put("__total__", 0);
						}

						int total = 0;
						for (ValueDiscrete valueDiscrete : issueDiscrete.getValues()) {
							// Evaluate to get good values that obey our preferences
							int evaluation = 1;

							try {
								if (space instanceof AdditiveUtilitySpace) {
									AdditiveUtilitySpace additiveSpace = (AdditiveUtilitySpace) space;
									evaluation = (int) Math.ceil(10 * ((EvaluatorDiscrete) ((AdditiveUtilitySpace) this.getUtilitySpace()).getEvaluator(id)).getEvaluation(valueDiscrete));
								}
							} catch (Exception e) {
								warn("Failed to getEvaluation(" + valueDiscrete + ")");
							}

							total += evaluation;
							frequencies.get(id).put(valueDiscrete.getValue(), evaluation);
						}
						frequencies.get(id).put("__total__", total);
					}
				});

				receiveMessage(this.getPartyId(), new Offer(this.getPartyId(), maxBid));
			}

			// According to Stacked Alternating Offers Protocol list includes
			// Accept, Offer and EndNegotiation actions only.
			double time = getTimeLine().getTime(); // Gets the time, running from t = 0 (start) to t = 1 (deadline).
			double willingness = concede(time);
			// The time is normalized, so agents need not be
			// concerned with the actual internal clock.

			// Increase our round timer.
			++round;

			// We need some time to understand our opponents
			// Means we have enough data and we don't (really) need to worry about nulls
			if (round <= 2) return new Offer(this.getPartyId(), maxBid);

			log("Willingness: " + willingness);

			// Last bid
			Bid last = history.peekLast().getSecond().getBid();
			if(last == null) last = this.generateRandomBid();

			// Make a proposal, needs to be HashMap to avoid a cast
			HashMap<Integer, Value> proposal = new HashMap<>(maxBid.getValues());

			// Roulette Wheel
			RouletteWheel rouletteWheel = new RouletteWheel();

			proposal.forEach((Integer id, Value value) -> {
				if (value instanceof ValueDiscrete) {
					// Store a list of pairs for later
					List<Pair<Double, String>> sublist = new ArrayList<>();
					double max = 0, total = 0;

					// Get the current Issue
					IssueDiscrete issueDiscrete = (IssueDiscrete) this.getUtilitySpace().getDomain().getIssues().get(id - 1);

					for (ValueDiscrete valueDiscrete : issueDiscrete.getValues()) {
						// score each choice, default is 0.5
						double evaluation = 0.5;

						// Only makes sense if we have an additive space
						if (this.getUtilitySpace() instanceof AdditiveUtilitySpace) {
							try {
								// Get the evaluation for that value
								evaluation = ((EvaluatorDiscrete) ((AdditiveUtilitySpace) this.getUtilitySpace()).getEvaluator(id)).getEvaluation(valueDiscrete);
							} catch (Exception e) {
								warn("Failed to getEvaluation(" + valueDiscrete + ")");
							}
						}
						// Get the frequency for the value if it's not yet been seen default to 1/number of issues
						double frequency = frequencies.get(id).containsKey(valueDiscrete.getValue()) ? frequencies.get(id).get(valueDiscrete.getValue()) / frequencies.get(id).get("__total__") : (1.0 / maxBid.getIssues().size());

						// Create a fitness for the value
						double score = evaluation * frequency * weights.get(id);
						max = Math.max(max, score);
						total += score;
						sublist.add(new Pair<>(score, valueDiscrete.getValue()));
					}

					rouletteWheel.updateMax(total);
					RouletteWheel.InnerWheel innerWheel = new RouletteWheel.InnerWheel(max, total, sublist);
					rouletteWheel.addInnerWheel(total, innerWheel);
				} else if (value instanceof ValueInteger) {
                    //<editor-fold desc="Value Integer Rules">
                    int sum = 0;
					int count = 0;

					for (Map.Entry<AgentID, Offer> agent : agents.entrySet()) {
						sum += ((ValueInteger) agent.getValue().getBid().getValue(id)).getValue();
						++count;
					}

					int bestValue = sum / count; // Start with the average
					int minDifference = Math.abs(bestValue - ((ValueInteger) maxBid.getValue(id)).getValue());

					for (Map.Entry<AgentID, Offer> agent : agents.entrySet()) {
						if (agent.getKey() == this.getPartyId()) continue;

						int difference = Math.abs(((ValueInteger) agent.getValue().getBid().getValue(id)).getValue() - ((ValueInteger) maxBid.getValue(id)).getValue());
						if (difference < minDifference) {
							minDifference = difference;
							bestValue = ((ValueInteger) agent.getValue().getBid().getValue(id)).getValue();
						}
					}

					proposal.put(id, new ValueInteger(lerp(bestValue, ((ValueInteger) maxBid.getValue(id)).getValue(), Math.pow(willingness, weights.get(id)))));
                    //</editor-fold>
				} else if (value instanceof ValueReal) {
                    //<editor-fold desc="Value Real Rules">
                    System.out.println("WE WERE TOLD THERE WOULDN'T BE ANY REAL'S!!!!");

					double sum = 0;
					long count = 0;

					for (Map.Entry<AgentID, Offer> agent : agents.entrySet()) {
						sum += ((ValueReal) agent.getValue().getBid().getValue(id)).getValue();
						++count;
					}

					double bestValue = sum / count; // Start with the average
					double minDifference = Math.abs(bestValue - ((ValueReal) maxBid.getValue(id)).getValue());

					for (Map.Entry<AgentID, Offer> agent : agents.entrySet()) {
						if (agent.getKey() == this.getPartyId()) continue;

						double difference = Math.abs(((ValueReal) agent.getValue().getBid().getValue(id)).getValue() - ((ValueReal) maxBid.getValue(id)).getValue());
						if (difference < minDifference) {
							minDifference = difference;
							bestValue = ((ValueReal) agent.getValue().getBid().getValue(id)).getValue();
						}
					}

					proposal.put(id, new ValueReal(lerp(bestValue, ((ValueReal) maxBid.getValue(id)).getValue(), Math.pow(willingness, weights.get(id)))));
                    //</editor-fold>
				} else {
					throw new UnsupportedOperationException("Unexpected value type!");
				}
			});

			// TODO: Improve readability
			// Spin the wheel, if additive
			if (this.getUtilitySpace() instanceof AdditiveUtilitySpace) {
				// Loop until within range, loop with an upper limit.
				// If we fail to find a good solution, just try to find one with a minimum value
				int c = 0;

				for (;
					(c < 10 * maxBid.getIssues().size() && !within(this.getUtility(new Bid(this.getUtilitySpace().getDomain(), proposal)), willingness - 0.1, willingness + 0.1)) ||
					(c < 20 * maxBid.getIssues().size() && this.getUtility(new Bid(this.getUtilitySpace().getDomain(), proposal)) <= willingness - 0.1);
				c++) {
					double outerValue = Math.random() * rouletteWheel.getTotal();

					for (int i = 0; i < rouletteWheel.getInnerWheels().size(); i++) {
						// Max, total, sublist
						RouletteWheel.InnerWheel issue = rouletteWheel.getInnerWheels().get(i);
						outerValue -= rouletteWheel.getMax() * issueBias - issue.getMax();

						if (outerValue <= 0) {
							// We have found our issue
							double innerValue = Math.random() * issue.getTotal();

							for (int j = 0; j < issue.getValuesList().size(); j++) {
								// Value, string
								Pair<Double, String> choice = issue.getValuesList().get(j);
								innerValue -= choice.getFirst();

								// We have found our choice
								if (innerValue <= 0) {
									proposal.put(i, new ValueDiscrete(choice.getSecond()));
									break;
								}
							}

							break;
						}
					}
				}
				log("The wheel spun " + c + " time(s)");
			}

			// Is the offer good enough?
			Bid bid = new Bid(this.getUtilitySpace().getDomain(), proposal);
			if (this.getUtilitySpace().getUtility(last) >= willingness) {
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
		} catch (Throwable throwable) {
			warn("CHOOSE ACTION FAILED, RETURNING EITHER MAX BID OR A RANDOM BID TO KEEP US IN THE RUNNING!!!");
			throwable.printStackTrace();
			return new Offer(this.getPartyId(), maxBid != null ? maxBid : this.generateRandomBid());
		}
	}

	/**
	 * This method is called to inform the party that another NegotiationParty chose an Action.
	 */
	@Override
	public void receiveMessage(AgentID sender, Action act) {
		try {
			super.receiveMessage(sender, act);

			log("receiveMessage(" + sender + "," + act + ")");

			if (act instanceof Offer) { // sender is making an offer
				Offer offer = (Offer) act;

				// storing last received offer
				history.add(new Pair<>(sender, offer));
				if (!agents.containsKey(sender)) agents.put(sender, offer);

				offer.getBid().getValues().forEach((Integer id, Value value) -> {
					// We only really care about discrete values
					if (value instanceof ValueDiscrete) {
						if (!frequencies.containsKey(id)) {
							frequencies.put(id, new HashMap<>());
							frequencies.get(id).put("__total__", 0);
						}

						String string = ((ValueDiscrete) value).getValue();
						frequencies.get(id).put(string, frequencies.get(id).containsKey(string) ? frequencies.get(id).get(string) + 1 : 1);
						frequencies.get(id).put("__total__", frequencies.get(id).get("__total__") + 1);
					}
				});
			} else if (act instanceof Accept) {
				log("Awesome!");
			} else if (act instanceof EndNegotiation) {
				warn("BOO!!!");
			}
		} catch(Throwable throwable) {
			warn("RECEIVE MESSAGE FAILED, RETURNING EITHER MAX BID OR A RANDOM BID TO KEEP US IN THE RUNNING!!!");
			throwable.printStackTrace();
		}
	}

	/**
	 * A human-readable description for this party.
	 */
	@Override
	public String getDescription() {
		String[] names = {"Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India", "Juliet", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango", "Uniform", "Victor", "Whiskey", "X-ray", "Yankee", "Zulu"};
		String[] descriptors = new String[]{"Submissive", "Soft", "Kind", "Reasonable", "Determined", "Firm", "Tough", "Angry", "Mad"};
		return descriptors[(int) Math.round(clamp(Math.log10(stubbornness) + 1, 0, descriptors.length))] + " " + names[(Agent23.created - 1) % names.length] + " " + getClass().getSimpleName();
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

	protected static class RouletteWheel {
		private Double max;
		private Double total;
		private List<InnerWheel> innerWheels;

		RouletteWheel(){
			this.max = 0d;
			this.total = 0d;
			setInnerWheels(new ArrayList<>());
		}


		public Double getMax() {
			return max;
		}

		public void updateMax (Double newMax) {
			this.max = Math.max(this.max, newMax);
		}

		public Double getTotal() {
			return total;
		}

		public void addToTotal(Double total) {
			this.total += total;
		}

		public List<InnerWheel> getInnerWheels() {
			return innerWheels;
		}

		public void setInnerWheels(List<InnerWheel> innerWheels) {
			this.innerWheels = innerWheels;
		}

		public void addInnerWheel(double total, InnerWheel innerWheel) {
			updateMax(total);
			addToTotal(total);
			getInnerWheels().add(innerWheel);
		}

		public static class InnerWheel{
			private Double max;
			private Double total;
			private List <Pair<Double, String>> valuesList;

			public InnerWheel(Double max, Double total, List<Pair<Double,String>> valuesList){
				this.max = max;
				this.total = total;
				this.valuesList = valuesList;
			}

			public Double getMax() {
				return max;
			}

			public void setMax(Double max) {
				this.max = max;
			}

			public Double getTotal() {
				return total;
			}

			public void setTotal(Double total) {
				this.total = total;
			}

			public List<Pair<Double, String>> getValuesList() {
				return valuesList;
			}

			public void setValuesList(List<Pair<Double, String>> valuesList) {
				this.valuesList = valuesList;
			}
		}
	}
}
