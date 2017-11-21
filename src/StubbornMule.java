import misc.Pair;
import negotiator.AgentID;
import negotiator.Bid;
import negotiator.actions.Accept;
import negotiator.actions.Action;
import negotiator.actions.Offer;
import negotiator.parties.AbstractNegotiationParty;
import negotiator.parties.NegotiationInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StubbornMule extends AbstractNegotiationParty {
	private String description = "Stubborn Mule";
	private Bid maxbid;

	private List<Pair<AgentID, Offer>> history = new ArrayList<>();
	private Map<AgentID, Offer> agents = new HashMap<>();

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

		if(maxbid == null) maxbid = this.getMaxUtilityBid();

		// We need some time to understand our opponents
		// Means we have enough data and we don't (really) need to worry about nulls
		if(history.size() == 0) return new Offer(this.getPartyId(), maxbid);

		// Last bid
		Bid last = history.get(history.size()-1).getSecond().getBid();

		// Is the offer good enough?
		if(this.getUtilitySpace().getUtility(last) >= 0.9) { // Small threshold
			System.out.println(getDescription() + ": Accepting offer " + this.getUtilitySpace().getUtility(last) + " " + last);
			return new Accept(this.getPartyId(), last);
		} else {
			// Offer is no good, propose our own
			System.out.println(getDescription() + ": Proposing offer " + this.getUtilitySpace().getUtility(maxbid) + " " + maxbid);
			return new Offer(this.getPartyId(), maxbid);
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
			if(!agents.containsKey(sender)) agents.put(sender, offer);
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
