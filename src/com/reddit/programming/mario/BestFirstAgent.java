package com.reddit.programming.mario;

import java.io.IOException;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ch.idsia.ai.agents.Agent;
import ch.idsia.mario.engine.GlobalOptions;
import ch.idsia.mario.engine.sprites.Mario;
import ch.idsia.mario.environments.Environment;

public final class BestFirstAgent extends RedditAgent implements Agent
{
	private boolean[] action;
	protected int[] marioPosition = null;
	protected Sensors sensors = new Sensors();
	private static final int simultaneousSearchers = Runtime.getRuntime().availableProcessors();
	private ExecutorService searchPool = Executors.newFixedThreadPool(simultaneousSearchers);
	private StateSearcher[] searchers = new StateSearcher[simultaneousSearchers];
	private Stack<Integer> decisions = new Stack<Integer>();
	private static final int budgetPerFrame = 40; //time, in milliseconds, that we can think per frame
	private int budget = 0;  //time, in milliseconds, that we can spend planning
	
	private static final boolean verbose1 = false;
	private static final boolean verbose2 = false;
	private static final boolean drawPath = true;
	// enable to single-step with the enter key on stdin
	private static final boolean stdinSingleStep = false;
	private static final int maxBreadth = 256;
	private static final int maxSteps = 500;
	private boolean won = false;

	MarioState ms = null, ms_prev = null;
	WorldState ws = null;
	float pred_x, pred_y;

	public BestFirstAgent() {
		super("BestFirstAgent");
		action = new boolean[Environment.numberOfButtons];
		reset();
	}

	@Override
	public void reset() {
		// disable enemies for the time being
//		GlobalOptions.pauseWorld = true;
		ms = null;
		marioPosition = null;
	}

//	private static final float lookaheadDist = 9*16;
	private float cost(MarioState s, MarioState initial) {
		if(s.dead)
			return Float.POSITIVE_INFINITY;

		int MarioX = (int)s.x/16 - s.ws.MapX;
		int goal = 21;
		// move goal back from the abyss
		//while(goal > 11 && s.ws.heightmap[goal] == 22) goal--;
		//no don't
		//
		float steps = MarioMath.stepsToRun((goal+s.ws.MapX)*16+8 - s.x, s.xa);
		// if we're standing in front of some thing, give the heuristic a
		// little help also adds a small penalty for walking up to something in
		// the first place
		if(MarioX < 21) {
			int thisY = s.ws.heightmap[MarioX];
			if(thisY == 22) { // we're either above or inside a chasm
				float edgeY = (22+s.ws.MapY)*16;
				// find near edge
				for(int i=MarioX-1;i>=0;i--) {
					if(s.ws.heightmap[i] != 22) {
						edgeY = (s.ws.heightmap[i]+s.ws.MapY)*16;
						break;
					}
				}
				if(s.y > edgeY+1) { // we're inside a chasm; don't waste time searching for a way out
					return Float.POSITIVE_INFINITY;
				}
			}
			float nextColY = (s.ws.heightmap[MarioX+1] + s.ws.MapY)*16;
			if(nextColY < s.y)
				steps += MarioMath.stepsToJump(s.y-nextColY);
		}

		return steps;
	}


	public static final Comparator<MarioState> msComparator = new MarioStateComparator();

	private boolean useless_action(int a, MarioState s) {
		if((a&MarioState.ACT_LEFT)>0 && (a&MarioState.ACT_RIGHT)>0) return true;
		if((a/MarioState.ACT_JUMP)>0) {
			if(s.jumpTime == 0 && !s.mayJump) return true;
			if(s.jumpTime <= 0 && !s.onGround && !s.sliding) return true;
		}
		return false;
	}

	private void addLine(float x0, float y0, float x1, float y1, int color) {
		if(drawPath && GlobalOptions.MarioPosSize < 400) {
			GlobalOptions.MarioPos[GlobalOptions.MarioPosSize][0] = (int)x0;
			GlobalOptions.MarioPos[GlobalOptions.MarioPosSize][1] = (int)y0;
			GlobalOptions.MarioPos[GlobalOptions.MarioPosSize][2] = color;
			GlobalOptions.MarioPosSize++;
			GlobalOptions.MarioPos[GlobalOptions.MarioPosSize][0] = (int)x1;
			GlobalOptions.MarioPos[GlobalOptions.MarioPosSize][1] = (int)y1;
			GlobalOptions.MarioPos[GlobalOptions.MarioPosSize][2] = color;
			GlobalOptions.MarioPosSize++;
		}
	}

//	private PriorityQueue<MarioState> prune_pq() {
		// first, swap pq2 and pq
//		PriorityQueue<MarioState> p = pq; pq = pq2; pq2 = p;
//		while(!pq2.isEmpty() && pq.size() < maxBreadth/2)
//			pq.add(pq2.remove());
//		pq2.clear();
//		return pq;
//		return null;
//	}

	private int searchForAction(MarioState initialState, WorldState ws) {
		budget += budgetPerFrame; //we get a frame's worth of time
		if (!decisions.isEmpty())
			return decisions.pop();	
		PriorityQueue<MarioState> pq = new PriorityQueue<MarioState>(20, msComparator);
		int i = 0;
		initialState.ws = ws;
		initialState.g = 0;
		initialState.cost = cost(initialState, initialState);
		initialState.pred = null;
		initialState.dead = false;
		// add initial set
		for(int a=1;a<16;a++) {
			if(useless_action(a, initialState))
				continue;
			MarioState ms = initialState.next(a, ws);
			ms.root_action = a;
			ms.cost = 1 + cost(ms, initialState);
			pq.add(ms);
			if(verbose2)
				System.out.printf("BestFirst: root action %d initial cost=%f\n", a, ms.cost);
		}
		PriorityQueue<MarioState>[] pqs = new PriorityQueue[searchers.length];
		//System.out.println("creating searchers");
		for (i = 0; i < pqs.length; i++) pqs[i] = new PriorityQueue<MarioState>(20, msComparator);
		i = 0;
		GlobalOptions.MarioPosSize = 0;
		while (!pq.isEmpty())
			pqs[i++%pqs.length].add(pq.remove());
		
		for (i = 0; i < searchers.length; i++){
			searchers[i] = new StateSearcher(initialState, ws, pqs[i], i);
			searchPool.execute(searchers[i]);
		}
		try {
			Thread.sleep(budget);
		} catch (InterruptedException e) {throw new RuntimeException("Interrupted from sleep searching for the best action");}
		budget = 0; //used up our whole budget (assuming we weren't interrupted)
		
		for (StateSearcher searcher: searchers)
			searcher.stop();
		for (StateSearcher searcher: searchers)
			while(!searcher.isStopped){}
		
		MarioState bestfound = null;
		for (StateSearcher searcher: searchers) {
			bestfound = marioMin(searcher.bestfound, bestfound);

//			if (verbose1)
//				System.out.printf("searcher_(%d): best root_action=%s cost=%f lookahead=%f\n",
//						searcher.id, actionToString(bestfound.root_action), bestfound.cost, bestfound.g);
		}

//		addLine(bestfound, 0xffffff);
		
		while(bestfound.pred != null){
			decisions.push(bestfound.action);
			bestfound = bestfound.pred;
		}
		int desiredSize = Math.max(5, Math.min(20, decisions.size() / 2));
		while(decisions.size() > desiredSize) decisions.remove(0);
		
//		if (verbose1){
//			System.out.println("Decisions made:");
//			System.out.println(decisionsToString(decisions));
//		}
		
		if (decisions.empty()){
			if (verbose1)
				System.err.println("NO PLAN FOUND?");
			return bestfound.root_action;
		}

		// return best so far
		return searchForAction(null,null);
	}

		
	private class StateSearcher implements Runnable {
		private final PriorityQueue<MarioState> pq;
		private final MarioState initialState;
		private final WorldState ws;
		private final int id;
		private boolean shouldStop = false;
		public boolean isStopped = false;
		private MarioState bestfound;
		private int DrawIndex = 0;
		
		public StateSearcher(MarioState initialState, WorldState ws, PriorityQueue<MarioState> pq, int id) {
			this.pq = pq; this.ws = ws; 
			this.initialState = initialState; this.bestfound = null;
			this.id = id; DrawIndex = id;
		}

		public void stop() {
			this.shouldStop = true;
		}
		
		public void run() {
			doRun();
			isStopped = true;
		}
		
		private void doRun() {
			int n = 0;
			bestfound = pq.peek();
			while((!shouldStop) && (!pq.isEmpty())) {
//				if(pq.size() > maxBreadth)
//					pq = prune_pq();
			
				MarioState next = pq.remove();

//				int color = (int) Math.min(255, 10000*Math.abs(next.cost - next.pred.cost));
//				color = color|(color<<8)|(color<<16);
//				addLine(next.x, next.y, next.pred.x, next.pred.y, color);
				// next.cost can be infinite, and still at the head of the queue,
				// if the node got marked dead
				if(next.cost == Float.POSITIVE_INFINITY) continue;


				bestfound = marioMin(next,bestfound);
				for(int a=1;a<16;a++) {
					if(useless_action(a, next))
						continue;
					MarioState ms = next.next(a, next.ws);

					if (DrawIndex >= 400)
					{
						DrawIndex = 0;
					}

					if(ms.dead) continue;
					ms.pred = next;

					// if we die, prune our predecessor node that got us here
					if(ms.dead) {
						// removing things from a priority queue is ridiculously
						// slow, so we'll just mark it dead
						ms.pred.cost = Float.POSITIVE_INFINITY;
						continue;
					}

					float h = cost(ms, initialState);
					ms.g = next.g + 1;
					ms.cost = ms.g + h;// + ((a&ACT_JUMP)>0?0.0001f:0);
					n++;
					if(h <= 0) {
						if(verbose1) {
//							System.out.printf("BestFirst: searched %d iterations; best a=%s cost=%f lookahead=%f\n", 
//									n, actionToString(ms.root_action), ms.cost, ms.g);
							MarioState s;
							if(GlobalOptions.MarioPosSize > 400-46)
								GlobalOptions.MarioPosSize = 400-46;
							for(s = ms;s != initialState;s = s.pred) {
								if(verbose2) {
									System.out.printf("state %d: ", (int)s.g);
									s.print();
								}
							}
							bestfound = ms;
							return;
						}
					}
					pq.add(ms);
				}
			}
		}

		private void addToDrawPath(MarioState mario) {
			GlobalOptions.MarioPos[DrawIndex] = new int[]{(int)mario.x, (int)mario.y, costToTransparency(mario.cost)};
			DrawIndex += simultaneousSearchers;
			if (DrawIndex >= 400)
				DrawIndex = id;
		}
	}
	
	public static int costToTransparency(float cost) {
		if (cost <= 0) return 80;
		return Math.max(0, 40-(int)cost);
	}

	public static MarioState marioMin(MarioState a, MarioState b) {
		if(a == null) return b;
		if(b == null) return a;
		// compare heuristic cost only
		if(a.cost - a.g <= b.cost - b.g) return a;
		return b;
	}

	@Override
	public boolean[] getAction(Environment observation)
	{
		if(won) // we won!  we can't do anything!
			return action;

		sensors.updateReadings(observation);
		marioPosition = sensors.getMarioPosition();
		float[] mpos = observation.getMarioFloatPos();
		if(ms == null) {
			// assume one frame of falling before we get an observation :(
			ms = new MarioState(mpos[0], mpos[1], 0.0f, 3.0f);
		} else {
			//System.out.println(String.format("mario x,y=(%5.1f,%5.1f)", mpos[0], mpos[1]));
			if(mpos[0] != pred_x || mpos[1] != pred_y) {
				// generally this shouldn't happen, unless we mispredict
				// something.  currently if we stomp an enemy then we don't
				// predict that and get confused.

				// but it will happen when we win, cuz we have no idea we won
				// and it won't let us move.  well, let's guess whether we won:
				if(mpos[0] > 4000 && mpos[0] == ms_prev.x && mpos[1] == ms_prev.y) {
					System.out.println("ack, can't move.  assuming we just won");
					won = true;
					return action;
				}
				if(verbose1)
					System.out.printf("mario state mismatch (%f,%f) -> (%f,%f); attempting resync\n",
							ms.x,ms.y, mpos[0], mpos[1]);
				resync(observation);
			}
		}
		// resync these things all the time
		ms.mayJump = observation.mayMarioJump();
		ms.onGround = observation.isMarioOnGround();
		ms.big = observation.getMarioMode() > 0;

		super.UpdateMap(sensors);

		if(verbose2) {
			float[] e = observation.getEnemiesFloatPos();
			for(int i=0;i<e.length;i+=3) {
				System.out.printf(" e %d %f,%f\n", (int)e[i], e[i+1], e[i+2]);
			}
		}

		// quantize mario's position to get the map origin
		if(ws == null)
			ws = new WorldState(sensors.levelScene, mpos, observation.getEnemiesFloatPos());
		else
			ws.update(sensors.levelScene, mpos, observation.getEnemiesFloatPos());

		int next_action = searchForAction(ms, ws);
		if(next_action/MarioState.ACT_JUMP > 0)
			next_action = (next_action&7) + 8;
		ms_prev = ms;
		ms = ms.next(next_action, ws);
		pred_x = ms.x;
		pred_y = ms.y;
		if(verbose2) {
			System.out.printf("MarioState (%f,%f,%f,%f) -> action %d -> (%f,%f,%f,%f)\n",
				ms_prev.x, ms_prev.y, ms_prev.xa, ms_prev.ya,
				next_action,
				ms.x, ms.y, ms.xa, ms.ya);
		}
		//System.out.println(String.format("action: %d; predicted x,y=(%5.1f,%5.1f) xa,ya=(%5.1f,%5.1f)",
		//		next_action, ms.x, ms.y, ms.xa, ms.ya));

		action[Mario.KEY_SPEED] = (next_action&MarioState.ACT_SPEED)!=0;
		action[Mario.KEY_RIGHT] = (next_action&MarioState.ACT_RIGHT)!=0;
		action[Mario.KEY_LEFT] = (next_action&MarioState.ACT_LEFT)!=0;
		action[Mario.KEY_JUMP] = (next_action&MarioState.ACT_JUMP)!=0;

		if(stdinSingleStep) {
			try {
				System.in.read();
			} catch(IOException e) {}
		}

		return action;
	}

	private void resync(Environment observation) {
		System.out.println("Out of sync, resyncing");
		decisions.removeAllElements();
		float[] mpos = observation.getMarioFloatPos();
		ms.x = mpos[0]; ms.y = mpos[1];
		//ms.mayJump = observation.mayMarioJump();
		//ms.onGround = observation.isMarioOnGround();
		//ms.big = observation.getMarioMode() > 0;
		// again, Mario's iteration looks like this:
		//   xa',ya'[n] = xa,ya[n-1] + lastmove_sx,y
		//   x,y[n] = x,y[n-1] + xa',ya'[n]
		//   xa,ya[n] = xa',ya'[n] * damp_x,y

		// lastmove_s was guessed wrong, or we wouldn't be out of sync.  we can
		// directly get the new xa and ya, as long as no collisions occurred.
		// if there *was* a collision and xa,ya are wrong, they probably will
		// be corrected by each call next()
		if(ms_prev != null) {
			ms.xa = (ms.x - ms_prev.x) * 0.89f;
			ms.ya = (ms.y - ms_prev.y) * 0.85f;
		}
	}
}
