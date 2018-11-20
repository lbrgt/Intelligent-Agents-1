package template;

import java.io.File;
//the list of imports
import java.util.ArrayList;
import java.util.List;
//import java.util.Random;

import logist.LogistSettings;
//import logist.Measures;
import logist.behavior.AuctionBehavior;
import logist.config.Parsers;
import logist.agent.Agent;
import logist.simulation.Vehicle;
import logist.plan.Plan;
import logist.task.Task;
import logist.task.TaskDistribution;
import logist.task.TaskSet;
import logist.topology.Topology;
import logist.topology.Topology.City;

import template.VehiclePlan;
import template.VehiclePlan.SingleAction;
import template.StochasticLocalSearch;

/**
 * A very simple auction agent that assigns all tasks to its first vehicle and
 * handles them sequentially.
 * 
 */
public class AuctionMain24 implements AuctionBehavior {

	private Topology topology;
	private TaskDistribution td;
	private Agent agent;
	//private Random random;
	private Vehicle vehicle;
	//private City currentCity;
	
	private double maximalDistanceEstimator = 0;
	
	private int numberOpponents = 1;
	
	// Effective global plan with won tasks
	private ArrayList<VehiclePlan> currentGlobalPlan = new ArrayList<VehiclePlan>();
	private ArrayList<Task> wonTasks = new ArrayList<Task>();
	private double globalCost = 0;
	
	// Hypothetical global plan with the newly auctioned task
	private ArrayList<VehiclePlan> hypoPlan = new ArrayList<VehiclePlan>();
	private double hypoCost = 0;
	// Bids history
	private ArrayList<Long[]> bidsHistory = new ArrayList<Long[]>();
	
	// Define default timeouts (updated by XML settings file)
	protected long timeout_setup = 5000;
	protected long timeout_plan  = 5000;
	protected long timeout_bid   = 5000;
	
	private class Path{
		// A class containing all the towns a vehicle will travel through. 
		private ArrayList<City>   path = new ArrayList<City>();
		private ArrayList<Double> load = new ArrayList<Double>();
		private Vehicle vehicle = null;
		
		public Path(VehiclePlan vehiclePlan, Vehicle vehicle) {
			this.convertVehiclePlan(vehiclePlan, vehicle);
		}
		
		public void convertVehiclePlan(VehiclePlan vehiclePlan, Vehicle vehicle) {
			// Reset the stored path
			this.path = new ArrayList<City>();
			this.load = new ArrayList<Double>();
			this.vehicle = vehicle;
			
			this.path.add(vehicle.getCurrentCity());
			this.load.add(0.0);
			
			// Build the path
			List<City> itinerary = null;
			for(SingleAction a : vehiclePlan.plan) {
				if(a.action == VehiclePlan.PICKUP) 
					itinerary = path.get(path.size()-1).pathTo(a.task.pickupCity);
				else if(a.action == VehiclePlan.DELIVER) 
					itinerary = path.get(path.size()-1).pathTo(a.task.deliveryCity);
				
				if(itinerary.isEmpty()) {
					// The vehicle does not move for this SingleAction
					this.load.set(load.size()-1,a.load);
					continue;
				}
				for(City c : itinerary) {
					this.path.add(c);
					this.load.add(a.load);
				}
			}
		}
		
		private boolean generatesOverload(int start, int end, int weight) {
			for(int c=start; c<=end; c++) {
				if(this.load.get(c) + weight > this.vehicle.capacity())
					return true;
			}
			return false;
		}
		public double computeDirectPotential(TaskDistribution td) {
			// For new tasks directly along the path. Returns probability of finding new tasks. 
			double cumulatedProba = 0;
			int probaCount = 0;
			for(int c1=0; c1<this.path.size(); c1++) {
				for(int c2=c1; c2<this.path.size(); c2++) {
					if(td.weight(path.get(c1), path.get(c2)) < 
							this.vehicle.capacity()-this.load.get(c1) &&
							!generatesOverload(c1,c2,td.weight(path.get(c1), path.get(c2)))){
						// If the task can be picked up. 
						cumulatedProba += td.probability(path.get(c1), path.get(c2));
						probaCount++;
					}
				}
			}
			// Take into account empty plans. 
			if(probaCount == 0)
				return 0;
			else
				return cumulatedProba/probaCount;
		}
		
		public double computeIndirectPotential(TaskDistribution td) {
			// Returns the expected cost for a small deviation from the path
			double cumulatedCost = 0;
			double costCount = 0;
			// From each city from the path
			for(int c1=0; c1<this.path.size()-1; c1++) {
				// Get its neighbours
				for(City n : path.get(c1).neighbors()) {
					// See any of these neighbours can join back the next city on the path
					for(City d : n.neighbors()) {
						if(d.id == path.get(c1+1).id || d.id == path.get(c1).id) {
							int back = 0; // Index of city to return to after wandering of the path. 
							if(d.id == path.get(c1).id)
								back = c1;
							else
								back = c1+1;
							// We found a detour! Compute its potential:
							for(int c2 = c1; c2<this.path.size(); c2++) {
								if(td.weight(n, path.get(c2)) < 
										this.vehicle.capacity()-this.load.get(c1) && 
										!generatesOverload(c1,c2,td.weight(path.get(c1), path.get(c2)))){

									cumulatedCost += (path.get(c1).distanceTo(n)+n.distanceTo(path.get(back)) -
											path.get(c1).distanceTo(path.get(back)))*this.vehicle.costPerKm()*
											td.probability(n, path.get(c2));
									costCount += td.probability(n, path.get(c2));
								}
							}
						}
					}
				}
			}
			// Take into account empty plans. 
			if(costCount == 0)
				return 0;
			else
				return cumulatedCost/costCount;
		}
	}

	private void estimateMaximalNeighbourDistance() {
		for(City c1 : this.topology.cities()) 
			for(City c2 : c1.neighbors()) 
				if(this.maximalDistanceEstimator < c1.distanceTo(c2)) 
					this.maximalDistanceEstimator = c1.distanceTo(c2);
	}
	
	private ArrayList<VehiclePlan> buildGlobalPlanFromTasks(ArrayList<Task> tasks, long timeout) {
	
		ArrayList<VehiclePlan> plan = new ArrayList<VehiclePlan>();
		for(Vehicle v : this.agent.vehicles()) {
			if(v.id() == this.vehicle.id()) {
				plan.add( new VehiclePlan(this.vehicle) );
				plan.get(plan.size()-1).initPlan(tasks);
			}
			else {
				plan.add( new VehiclePlan(v) );
				plan.get(plan.size()-1).initPlan(null);
			}
		}
		// Compute a plan for the given set of won tasks
		StochasticLocalSearch.setGlobalPlan(plan);
		StochasticLocalSearch.setTaskSet(tasks);
		plan = StochasticLocalSearch.slSearch(plan,time_out-500); // !DEBUG

		return plan;
	}
	
	@Override
	public void setup(Topology topology, TaskDistribution distribution, Agent agent) {
		
		// this code is used to get the timeouts
        LogistSettings ls = null;
        try {
            ls = Parsers.parseSettings("config" + File.separator + "settings_auction.xml");
        }
        catch (Exception exc) {
            System.out.println("There was a problem loading the configuration file.");
        }
        
        // the setup method cannot last more than timeout_setup milliseconds
        timeout_setup = ls.get(LogistSettings.TimeoutKey.SETUP);
        // the plan method cannot execute more than timeout_plan milliseconds
        timeout_plan = ls.get(LogistSettings.TimeoutKey.PLAN);
        // the bid method cannot execute more than timeout_bid milliseconds
        timeout_bid = ls.get(LogistSettings.TimeoutKey.BID);
		
		this.topology = topology;
		this.td = distribution;
		this.agent = agent;
		
		// Get the biggest vehicle
		Vehicle biggestVehicle = null;
		int bestCapa = 0;
		for(Vehicle v : this.agent.vehicles()) {
			if(v.capacity() > bestCapa) {
				bestCapa = v.capacity();
				biggestVehicle = v;
			}
		}
		this.vehicle = biggestVehicle;
		
		//this.currentCity = vehicle.homeCity();
		
		estimateMaximalNeighbourDistance();

		//long seed = -9019554669489983951L * currentCity.hashCode() * agent.id();
		//this.random = new Random(seed);

	}

	@Override
	public void auctionResult(Task previous, int winner, Long[] bids) {
		// Long[] bids - The bid placed by each agent for the previous task -> bids.length == NumAgents
		this.numberOpponents = bids.length-1;
		
		if (winner == agent.id()) {
			// Add the task to the list of won tasks
			this.wonTasks.add(previous);
			this.globalCost = this.hypoCost;
			this.currentGlobalPlan = this.hypoPlan;
		}
		
		//Display the last bids
		bidsHistory.add(bids);
		for(int i=0; i<bids.length; i++)
			System.out.println(bids[i]);
	}
	
	@Override
	public Long askPrice(Task task) {

		// 1) Check for extreme cases
		if (vehicle.capacity() < task.weight)
			return null;
		if(this.numberOpponents == 0) {
			return (long) 1000000000;
		}
		
		
		// 2) Compute the hypothetical plan with the newly auctioned task
		this.wonTasks.add(task);

		this.hypoPlan = buildGlobalPlanFromTasks(wonTasks, this.timeout_bid);
		this.hypoCost = StochasticLocalSearch.computeCost();
		this.wonTasks.remove(wonTasks.size()-1);
		
		// 3) Define floor bid

		double floor_bid = this.hypoCost-this.globalCost;
		if(floor_bid < 0)
			floor_bid = 0; // The new plan is shorter! SLS sort of screwed up before. 
		
                    	
		// 4) Compute task's potential
		ArrayList<Path> paths = new ArrayList<Path>();
		// Convert each VehiclePlan to an explicit itinerary.
		double probaTaskOnPath = 0;
		double expectedCostOfNearByTask = 0;
		for(VehiclePlan vp : hypoPlan) {
			paths.add(new Path(vp, vp.vehicle));
			probaTaskOnPath += paths.get(paths.size()-1).computeDirectPotential(this.td);
			expectedCostOfNearByTask += paths.get(paths.size()-1).computeIndirectPotential(this.td)/
					(this.maximalDistanceEstimator*2*vp.vehicle.costPerKm()); // Normalised cost of near tasks
		}
		probaTaskOnPath /= this.agent.vehicles().size(); // Averaged probability of having a new task on the path
		expectedCostOfNearByTask /= this.agent.vehicles().size(); // Averaged normalised cost of near tasks
		System.out.println("probaTaskOnPath: "+probaTaskOnPath);
		System.out.println("expectedCostOfNearByTask: "+expectedCostOfNearByTask);

		
		// 5) Evaluate opponents' behaviour (get in their head!)
		// TODO
		double[] opponent_mean_bid;
		double best_opponent_mean = 0.0;
		double agent_mean_bid = 0.0;
		
		if(this.bidsHistory.size()>0) {
			opponent_mean_bid = compute_mean_bid();
			best_opponent_mean = find_opponent_best_mean(opponent_mean_bid);
			agent_mean_bid = get_agent_mean_bid(opponent_mean_bid);
		}
		System.out.println("best_opponent_mean" + best_opponent_mean);
		System.out.println("agent_mean_bid" + agent_mean_bid);

		
		// 6) Compute bid
		// TODO ~ floor_bid + (floor_bid*0.2)*(1-potential) + mean_effect
		
		double bid = 100;

		return (long) Math.round(bid);
	}
	
	private void replaceTasks(ArrayList<Task> agentTasks) {
		for(VehiclePlan vp : this.currentGlobalPlan) {
			for(SingleAction sa : vp.plan) {
				for(Task t : agentTasks) {
					if(sa.task.id == t.id)
						sa.task = t;
				}
			}
		}
	}
	
	private double[] compute_mean_bid() {
		double[] opponent_mean = new double[this.numberOpponents+1];
		
		System.out.println("opponets "+ this.numberOpponents );
		for(int opponent=0; opponent<this.numberOpponents+1;opponent++) {
					
			for(int b = 0; b < this.bidsHistory.size();b++) {
				opponent_mean[opponent] += this.bidsHistory.get(b)[opponent];
			}
			opponent_mean[opponent]/=this.bidsHistory.size();
		}
		return opponent_mean;
	}
	private double find_opponent_best_mean(double[] mean_bid) {
		double min_mean = Double.MAX_VALUE; 
		int best_opponent = 0;
		for(int opponent=0; opponent<this.numberOpponents+1;opponent++) {
			if(opponent == agent.id())
				continue;
			
			System.out.println(mean_bid[opponent]);
			if (mean_bid[opponent]<min_mean) {
				best_opponent = opponent;
				min_mean = mean_bid[opponent];
			}			
		}
		return min_mean;
	}
	
	private double get_agent_mean_bid(double[] mean_bid) {
		double agent_mean_bid = 0.0;
		for(int opponent=0; opponent<this.numberOpponents+1;opponent++) {
			if(opponent == agent.id()) {
				agent_mean_bid = mean_bid[opponent];
				break;
			}
		}
		return agent_mean_bid ;
	}
	private List<Plan> produceLogistPlan(List<Vehicle> vehicles){
    	// Based on the globalPlan
    	
    	// Order of the plans matters! And don't forget to include empty plans
        List<Plan> plans = new ArrayList<Plan>();
        VehiclePlan plan_i = null;
        for(int i=0; i<vehicles.size();i++) {
        	// Find the right vehicle's plan
        	for(int j=0; j<this.currentGlobalPlan.size();j++) {
        		if(this.currentGlobalPlan.get(j).vehicle.id() == vehicles.get(i).id()) {
        			plan_i = this.currentGlobalPlan.get(j);
        			break;
        		}
        	}
        	if(plan_i != null && !plan_i.plan.isEmpty())
        		plans.add(plan_i.convertToLogistPlan());
        	else
        		plans.add(Plan.EMPTY);
        	plan_i = null;
        } 
        return plans;
    }

	@Override
	public List<Plan> plan(List<Vehicle> vehicles, TaskSet tasks) {
		
		ArrayList<Task> agentTasks = new ArrayList<Task>();
		for(Task t : tasks) {
			agentTasks.add(t);
		}
		
		if(this.timeout_bid < this.timeout_plan)
			this.currentGlobalPlan = buildGlobalPlanFromTasks(agentTasks, this.timeout_plan);
		else {
			System.out.println("Replacing tasks");
			replaceTasks(agentTasks);
		}
		
		List<Plan> plan = produceLogistPlan(this.agent.vehicles());

		return plan;
	}
}
