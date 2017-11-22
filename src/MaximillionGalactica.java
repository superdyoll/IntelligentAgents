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
import negotiator.utility.EvaluatorDiscrete;

import java.util.*;

public class MaximillionGalactica extends AbstractNegotiationParty {
    private String description = "Maximillion Galactica";
    private double stubbornness = 10_000;
    private int round = 0;
    private Bid maxbid;
    private Map<Bid, Double> discretePref;

    private List<Pair<AgentID, Offer>> history = new ArrayList<>();
    private Map<AgentID, Offer> agents = new HashMap<>();

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

        if (maxbid == null) maxbid = this.getMaxUtilityBid(); // TODO: REALLY FUCKING SLOW

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
        Bid last = history.get(history.size() - 1).getSecond().getBid();

        // Find the average
        Map<Integer, Value> averages = new HashMap<>(maxbid.getValues());
        Map<Integer, Value> proposal = new HashMap<>(maxbid.getValues());


        averages.forEach((Integer id, Value value) -> {
            if (value instanceof ValueDiscrete) {
                // Convert to additive space
                AdditiveUtilitySpace additiveUtilitySpace = (AdditiveUtilitySpace) utilitySpace;

                // Get the list of issues
                List<Issue> issues = additiveUtilitySpace.getDomain().getIssues();

                // Get the issue discrete for the current issue
                IssueDiscrete issueDiscrete = (IssueDiscrete) issues.get(id - 1);
                EvaluatorDiscrete evaluatorDiscrete = (EvaluatorDiscrete) additiveUtilitySpace.getEvaluator(id - 1);

                // Make a priority queue of the vlaues with their evaluations
                PriorityQueue<OrderedValue> priorityQueue = new PriorityQueue<>();

                // Check values isn't empty
                if (!issueDiscrete.getValues().isEmpty()) {
                    issueDiscrete.getValues().forEach((ValueDiscrete valueDiscrete) -> {
                        try {
                            priorityQueue.add(new OrderedValue(valueDiscrete, evaluatorDiscrete.getEvaluation(valueDiscrete)));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                    });

                    // Work the way down the priorities
                    boolean proposalSet = false;
                    OrderedValue previousOrderedValue;
                    OrderedValue currentOrderedValue = priorityQueue.poll();
                    while (!priorityQueue.isEmpty() && !proposalSet) {
                        previousOrderedValue = currentOrderedValue;
                        currentOrderedValue = priorityQueue.poll();
                        if (willingness > currentOrderedValue.evaluation) {
                            proposalSet = true;
                            proposal.put(id, previousOrderedValue.value);
                        }
                    }
                    if (!proposalSet) {
                        proposal.put(id, currentOrderedValue.value);
                    }
                }

            } else if (value instanceof ValueInteger) {
                int sum = 0;
                int count = 0;

                for (Map.Entry<AgentID, Offer> agent : agents.entrySet()) {
                    sum += ((ValueInteger) agent.getValue().getBid().getValue(id)).getValue();
                    ++count;
                }

                proposal.put(id, new ValueInteger(lerp(sum / count, ((ValueInteger) value).getValue(), willingness)));
                averages.put(id, new ValueInteger(sum / count));
            } else if (value instanceof ValueReal) {
                double sum = 0;
                int count = 0;

                for (Map.Entry<AgentID, Offer> agent : agents.entrySet()) {
                    sum += ((ValueReal) agent.getValue().getBid().getValue(id)).getValue();
                    ++count;
                }

                proposal.put(id, new ValueReal(lerp(sum / count, ((ValueReal) value).getValue(), willingness)));
                averages.put(id, new ValueReal(sum / count));
            } else {
                throw new UnsupportedOperationException("Unexpected value type!");
            }
        });

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

    protected class OrderedValue implements Comparable<OrderedValue> {
        private Value value;
        private Double evaluation;

        public OrderedValue(Value value, double evaluation) {
            this.value = value;
            this.evaluation = evaluation;
        }

        public Value getValue() {
            return value;
        }

        public void setValue(Value value) {
            this.value = value;
        }

        public double getEvaluation() {
            return evaluation;
        }

        public void setEvaluation(double evaluation) {
            this.evaluation = evaluation;
        }

        @Override
        public int compareTo(OrderedValue o) {
            return this.evaluation.compareTo(o.evaluation);
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
        // Describe the agents mood
        String[] descriptors = new String[]{"Submissive", "Soft", "Kind", "Reasonable", "Determined", "Firm", "Tough", "Angry", "Mad"};
        return descriptors[(int) Math.round(clamp(Math.log10(stubbornness) + 2, 0, descriptors.length))] + " " + description;
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
