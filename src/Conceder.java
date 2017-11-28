import misc.Pair;
import negotiator.AgentID;
import negotiator.Bid;
import negotiator.actions.Accept;
import negotiator.actions.Action;
import negotiator.actions.Offer;
import negotiator.issue.Value;
import negotiator.issue.ValueDiscrete;
import negotiator.parties.AbstractNegotiationParty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Conceder extends AbstractNegotiationParty {

    private double stubbornness = 10_000;
    private int round = 0;
    private Bid maxbid;

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
        return clamp01(- (Math.pow(stubbornness, clamp01(t)) / stubbornness) + 1);
    }

    @Override
    public Action chooseAction(List<Class<? extends Action>> list) {
        System.out.println(getDescription() + ": ChooseAction(" + list + ")");

        if(maxbid == null) maxbid = this.getMaxUtilityBid(); // TODO REALLY FUCKING SLOW

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
        if(round <= 2) return new Offer(this.getPartyId(), maxbid);

        // Last bid
        Bid last = history.get(history.size()-1).getSecond().getBid();

        Map<Integer, Value> proposal = new HashMap<>(maxbid.getValues());

        proposal.forEach((Integer id, Value value) -> {
            if (value instanceof ValueDiscrete){
                proposal.put(id, willingness > 0.5 ? value : generateRandomBidWithUtility(willingness).getValue(id));
            }else{
                System.out.println("We were told there would only be discrete!!!!! Default to MAX BID");
            }
        });

        // Is the offer good enough?
        Bid bid = new Bid(this.getUtilitySpace().getDomain(), new HashMap<>(proposal));
        if(this.getUtilitySpace().getUtility(last) >= this.getUtilitySpace().getUtility(bid)) {
            System.out.println(getDescription() + ": Accepting offer " + this.getUtilitySpace().getUtility(last) + " " + last);
            return new Accept(this.getPartyId(), last);
        } else {
            // Offer is no good, propose our own
            System.out.println(getDescription() + ": Proposing offer " + this.getUtilitySpace().getUtility(bid) + " " + bid);
            return new Offer(this.getPartyId(), bid);
        }
    }

    public Bid generateRandomBidWithUtility(double utilityThreshold) {
        Bid randomBid;
        double utility;
        do {
            randomBid = generateRandomBid();
            try {
                utility = utilitySpace.getUtility(randomBid);
            } catch (Exception e)
            {
                utility = 0.0;
            }
        }
        while (utility < utilityThreshold);
        return randomBid;
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

    @Override
    public String getDescription() {
        return "The Conceder";
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
