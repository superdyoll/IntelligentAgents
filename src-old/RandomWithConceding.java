import negotiator.AgentID;
import negotiator.Bid;
import negotiator.actions.Accept;
import negotiator.actions.Action;
import negotiator.actions.Offer;
import negotiator.parties.AbstractNegotiationParty;
import negotiator.parties.NegotiationInfo;

import java.util.List;

/**
 * ExampleAgent returns the bid that maximizes its own utility for half of the negotiation session.
 * In the second half, it offers a random bid. It only accepts the bid on the table in this phase,
 * if the utility of the bid is higher than Example Agent's last bid.
 */
public class RandomWithConceding extends AbstractNegotiationParty {
    private final String description = "Random with Conceding Agent";

    private Bid lastReceivedOffer; // offer on the table
    private Bid myLastOffer;

    private double stubbornness = 10_000;

    @Override
    public void init(NegotiationInfo info) {
        super.init(info);
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
     * How much are we willing to concede at time t?
     */
    private double conceed(double t) {
        return clamp01(- (Math.pow(stubbornness, clamp01(t)) / stubbornness) + 1);
    }

    /**
     * When this function is called, it is expected that the Party chooses one of the actions from the possible
     * action list and returns an instance of the chosen action.
     *
     * @param list
     * @return
     */
    @Override
    public Action chooseAction(List<Class<? extends Action>> list) {
        // According to Stacked Alternating Offers Protocol list includes
        // Accept, Offer and EndNegotiation actions only.
        double time = getTimeLine().getTime(); // Gets the time, running from t = 0 (start) to t = 1 (deadline).
        double willingness = conceed(time);
        // The time is normalized, so agents need not be
        // concerned with the actual internal clock.


        // First half of the negotiation offering the max utility (the best agreement possible) for Example Agent
        if (time < 0.5) {
            return new Offer(this.getPartyId(), this.getMaxUtilityBid());
        } else {

            // Accepts the bid on the table in this phase,
            // if the utility of the bid is higher than Example Agent's last bid.
            if (lastReceivedOffer != null
                    && myLastOffer != null
                    && this.utilitySpace.getUtility(lastReceivedOffer) > this.utilitySpace.getUtility(myLastOffer)) {

                return new Accept(this.getPartyId(), lastReceivedOffer);
            } else {
                // Offering a random bid
                myLastOffer = generateRandomBidWithUtility(willingness);
                return new Offer(this.getPartyId(), myLastOffer);
            }
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
     * @param sender
     * @param act
     */
    @Override
    public void receiveMessage(AgentID sender, Action act) {
        super.receiveMessage(sender, act);

        if (act instanceof Offer) { // sender is making an offer
            Offer offer = (Offer) act;

            // storing last received offer
            lastReceivedOffer = offer.getBid();
        }
    }

    /**
     * A human-readable description for this party.
     * @return
     */
    @Override
    public String getDescription() {
        return description;
    }

    private Bid getMaxUtilityBid() {
        try {
            return this.utilitySpace.getMaxUtilityBid();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}

