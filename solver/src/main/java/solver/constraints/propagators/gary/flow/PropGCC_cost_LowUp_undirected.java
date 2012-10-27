/**
 *  Copyright (c) 1999-2011, Ecole des Mines de Nantes
 *  All rights reserved.
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 *      * Redistributions of source code must retain the above copyright
 *        notice, this list of conditions and the following disclaimer.
 *      * Redistributions in binary form must reproduce the above copyright
 *        notice, this list of conditions and the following disclaimer in the
 *        documentation and/or other materials provided with the distribution.
 *      * Neither the name of the Ecole des Mines de Nantes nor the
 *        names of its contributors may be used to endorse or promote products
 *        derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE REGENTS AND CONTRIBUTORS ``AS IS'' AND ANY
 *  EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED. IN NO EVENT SHALL THE REGENTS AND CONTRIBUTORS BE LIABLE FOR ANY
 *  DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package solver.constraints.propagators.gary.flow;

import choco.kernel.ESat;
import choco.kernel.common.util.procedure.PairProcedure;
import choco.kernel.memory.IStateInt;
import solver.Solver;
import solver.constraints.Constraint;
import solver.constraints.propagators.Propagator;
import solver.constraints.propagators.PropagatorPriority;
import solver.exception.ContradictionException;
import solver.variables.EventType;
import solver.variables.IntVar;
import solver.variables.Variable;
import solver.variables.delta.IGraphDeltaMonitor;
import solver.variables.graph.DirectedGraph;
import solver.variables.graph.UndirectedGraphVar;
import solver.variables.setDataStructures.SetType;
import solver.variables.setDataStructures.ISet;
import solver.variables.graph.graphOperations.connectivity.StrongConnectivityFinder;

import java.util.BitSet;

/**
 * Propagator for Global Cardinality Constraint (GCC) AC for an undirected graph variable
 * foreach i, low[i]<=|{v = i | for any v in vars}|<=up[i]
 * <p/>
 * Uses Regin algorithm
 * Runs in O(m.n) worst case time for the initial propagation and then in O(n+m) time
 * per arc removed from the support
 * Has a good average behavior in practice
 * <p/>
 * Runs incrementally for maintaining a matching
 * <p/>
 *
 * @author Jean-Guillaume Fages
 */
public class PropGCC_cost_LowUp_undirected extends Propagator<Variable> {

	//***********************************************************************************
	// VARIABLES
	//***********************************************************************************

	private int n, n2;
	private UndirectedGraphVar g;
	private DirectedGraph digraph;
	private int[] nodeSCC;
	private DirectedRemProc remProc;
	protected final IGraphDeltaMonitor gdm;
	private StrongConnectivityFinder SCCfinder;
	// for augmenting matching (BFS)
	private int[] father;
	private BitSet in;
	int[] fifo;
	private int[] lb,ub;
	private IStateInt totalFlow;
	private IStateInt[] flow;
	private IntVar maxFlowValue;
	private int costValue;
	private IntVar flowCost;
	private int[][] costMatrix;
	private int[] fb_poids;
	private int cycleNode;

	//***********************************************************************************
	// CONSTRUCTORS
	//***********************************************************************************

	/**
	 * Global Cardinality Constraint (GCC) for an undirected graph variable
	 * foreach i, low[i]<=|{v = i | for any v in vars}|<=up[i]
	 *
	 * @param g
	 * @param low
	 * @param up
	 * @param constraint
	 * @param sol
	 */
	public PropGCC_cost_LowUp_undirected(UndirectedGraphVar g, IntVar maxFlowValue, IntVar cost,
										 int[][] costMatrix, int[] low, int[] up,
										 Constraint constraint, Solver sol) {
		super(new Variable[]{g,maxFlowValue,cost}, sol, constraint, PropagatorPriority.QUADRATIC, false);
		this.gdm = g.monitorDelta(this);
		this.g = g;
		this.flowCost = cost;
		this.costMatrix = costMatrix;
		n = g.getEnvelopGraph().getNbNodes();
		n2 = n*2;
		if(n2!=low.length){
			throw new UnsupportedOperationException();
		}
		fifo = new int[n2];
		digraph = new DirectedGraph(solver.getEnvironment(), n2 + 1, SetType.LINKED_LIST,false);
		remProc = new DirectedRemProc();
		father = new int[n2+1];
		in = new BitSet(n2);
		SCCfinder = new StrongConnectivityFinder(digraph);
		//
		this.lb = low;
		this.ub = up;
		this.flow = new IStateInt[n2];
		for(int i=0; i<n2; i++){
			flow[i] = environment.makeInt(0);
		}
		totalFlow = environment.makeInt(0);
		this.maxFlowValue = maxFlowValue;
		fb_poids = new int[n2+1];
	}

	@Override
	public String toString() {
		StringBuilder st = new StringBuilder();
		st.append("PropGCC_cost_LowUp_undirected(");
		st.append(maxFlowValue.getName()).append(",");
		st.append(g.getName()).append(")");
		return st.toString();
	}

	//***********************************************************************************
	// Initialization
	//***********************************************************************************

	private void buildDigraph() throws ContradictionException {
		digraph.desactivateNode(n2);
		costValue = 0;
		for (int i = 0; i < n2; i++) {
			flow[i].set(0);
			digraph.getSuccessorsOf(i).clear();
			digraph.getPredecessorsOf(i).clear();
		}
		totalFlow.set(0);
		for (int i = 0; i < n; i++) {
			ISet nei = g.getEnvelopGraph().getSuccessorsOf(i);
			for(int j=nei.getFirstElement();j>=0;j=nei.getNextElement()){
				digraph.addArc(i,j+n);
			}
		}
	}

	//***********************************************************************************
	// MATCHING
	//***********************************************************************************

	private void repairMatching() throws ContradictionException {
		// find feasible (min) flow
		for (int i=0; i<n; i++) {
			while(flow[i].get()<lb[i]){
				assignVariable(i);
			}
		}
		for (int i=n; i<n2; i++) {
			while(flow[i].get()<lb[i]){
				useValue(i);
			}
		}
		maxFlowValue.updateLowerBound(totalFlow.get(), this);
		if(maxFlowValue.getUB()>totalFlow.get()){
			// find max flow (case not tested yet)
			for (int i=0; i<n; i++) {
				while(flow[i].get()<ub[i]){
					int mate = augmentPath_BFS(i);
					if(mate!=-1){
						assignVariable(i,mate);
					}else{
						break;
					}
				}
			}
			maxFlowValue.updateUpperBound(totalFlow.get(),this);
		}
		// eval support;
		costValue = 0;
		for(int i=0;i<n;i++){
			ISet nei = digraph.getPredecessorsOf(i);
			for(int j=nei.getFirstElement();j>=0;j=nei.getNextElement()){
				costValue += costMatrix[i][j];
			}
		}
		int c1 = costValue;
		// find min cost max flow
		while(negativeCircuitExists()){
			applyNegativeCircuit();
			digraph.desactivateNode(n2);
			costValue = 0;
			for(int i=0;i<n;i++){
				ISet nei = digraph.getPredecessorsOf(i);
				for(int j=nei.getFirstElement();j>=0;j=nei.getNextElement()){
					costValue += costMatrix[i][j];
				}
				flow[i].set(nei.getSize());
				nei = digraph.getSuccessorsOf(i+n);
				flow[i+n].set(nei.getSize());
			}
		}
		costValue = 0;
		for(int i=0;i<n;i++){
			ISet nei = digraph.getPredecessorsOf(i);
			for(int j=nei.getFirstElement();j>=0;j=nei.getNextElement()){
				costValue += costMatrix[i][j];
			}
		}
		flowCost.updateLowerBound(costValue, this);
	}

	private boolean negativeCircuitExists() {
		// initialization
		digraph.activateNode(n2);
		for (int i = 0; i < n; i++) {//TODO verifier
			if (flow[i].get()<ub[i]) {
				digraph.addArc(n2, i);
			}
			if(flow[i].get()>lb[i]){
				digraph.addArc(i,n2);
			}
		}
		for (int i = n; i < n2; i++) {
			if (flow[i].get()<ub[i]) {
				digraph.addArc(i, n2);
			}
			if(flow[i].get()>lb[i]){
				digraph.addArc(n2, i);
			}
		}

		int max = costValue+10;// big M
		for(int i=0;i<=n2;i++){
			fb_poids[i] = max;
			father[i] = -1;
		}
		fb_poids[n2] = 0;
		assert digraph.getSuccessorsOf(n2).getSize()>=0;
		// Bellman-Ford algorithm
		for(int iter=1;iter<n2;iter++){
			for(int i=0;i<=n2;i++){
				ISet nei = digraph.getSuccessorsOf(i);
				for(int j=nei.getFirstElement();j>=0;j=nei.getNextElement()){
					int paux = fb_poids[i];
					if(i!=n2 && j!=n2){
						if(i<j){
							paux += costMatrix[i][j];
						}else{
							paux -= costMatrix[i][j];
						}
					}
					if(paux<fb_poids[j]){
						fb_poids[j] = paux;
						father[j] = i;
					}
				}
			}
		}
		for(int i=0;i<=n2;i++){
			ISet nei = digraph.getSuccessorsOf(i);
			for(int j=nei.getFirstElement();j>=0;j=nei.getNextElement()){
				int paux = fb_poids[i];
				if(i!=n2 && j!=n2){
					if(i<j){
						paux += costMatrix[i][j];
					}else{
						paux -= costMatrix[i][j];
					}
				}
				if(paux<fb_poids[j]){
					cycleNode = j;
					return true;
				}
			}
		}
		digraph.desactivateNode(n2);
		return false;
	}

	private void applyNegativeCircuit() {
		int i = cycleNode;
		int p = father[cycleNode];
		if(p<0){
			throw new UnsupportedOperationException();
		}
		in.clear();
		do{
			in.set(i);
			i = p;
			p = father[p];
			assert p>=0;
		}while(!in.get(p));
		cycleNode = i;
		do{
			digraph.removeArc(p,i);
			digraph.addArc(i,p);
			i = p;
			p = father[p];
			assert p>=0;
		}while(i!=cycleNode);
	}

	private void assignVariable(int i) throws ContradictionException {
		int mate = augmentPath_BFS(i);
		assignVariable(i,mate);
	}

	private void assignVariable(int i, int mate) throws ContradictionException {
		if (mate != -1) {
			flow[mate].add(1);
			flow[i].add(1);
			totalFlow.add(1);
			int tmp = mate;
			while (tmp != i) {
				digraph.removeArc(father[tmp], tmp);
				digraph.addArc(tmp, father[tmp]);
				tmp = father[tmp];
			}
		} else {
			contradiction(g, "no match");
		}
	}

	private int augmentPath_BFS(int root) {
		in.clear();
		int indexFirst = 0, indexLast = 0;
		fifo[indexLast++] = root;
		int x, y;
		ISet succs;
		while (indexFirst != indexLast) {
			x = fifo[indexFirst++];
			succs = digraph.getSuccessorsOf(x);
			for (y = succs.getFirstElement(); y >= 0; y = succs.getNextElement()) {
				if (!in.get(y)) {
					father[y] = x;
					fifo[indexLast++] = y;
					in.set(y);
					if (flow[y].get()<this.ub[y]){
						assert (y>=n);
						return y;
					}
				}
			}
		}
		return -1;
	}

	private void useValue(int i) throws ContradictionException {
		int mate = swapValue_BFS(i);
		if (mate != -1) {
			if(mate<n){
				flow[mate].add(1);
				totalFlow.add(1);
			}else{
				flow[mate].add(-1);
			}
			flow[i].add(1);
			int tmp = mate;
			while (tmp != i) {
				digraph.removeArc(tmp, father[tmp]);
				digraph.addArc(father[tmp], tmp);
				tmp = father[tmp];
			}
		} else {
			contradiction(g, "no match");
		}
	}

	private int swapValue_BFS(int root) {
		in.clear();
		int indexFirst = 0, indexLast = 0;
		fifo[indexLast++] = root;
		int x, y;
		ISet succs;
		while (indexFirst != indexLast) {
			x = fifo[indexFirst++];
			succs = digraph.getPredecessorsOf(x);
			for (y = succs.getFirstElement(); y >= 0; y = succs.getNextElement()) {
				if (!in.get(y)) {
					father[y] = x;
					fifo[indexLast++] = y;
					in.set(y);
					if ((y<n && flow[y].get()<ub[y]) || (y>=n && flow[y].get()>this.lb[y])){
						return y;
					}
				}
			}
		}
		return -1;
	}

	//***********************************************************************************
	// PRUNING
	//***********************************************************************************

	private void buildSCC() {
		digraph.desactivateNode(n2);
		digraph.activateNode(n2);
		for (int i = 0; i < n; i++) {//TODO verifier
			if (flow[i].get()<ub[i]) {
				digraph.addArc(n2, i);
			}
			if(flow[i].get()>lb[i]){
				digraph.addArc(i,n2);
			}
		}
		for (int i = n; i < n2; i++) {
			if (flow[i].get()<ub[i]) {
				digraph.addArc(i, n2);
			}
			if(flow[i].get()>lb[i]){
				digraph.addArc(n2, i);
			}
		}
		SCCfinder.findAllSCC();
		nodeSCC = SCCfinder.getNodesSCC();
		digraph.desactivateNode(n2);
	}

	private void filter() throws ContradictionException {
		if(!maxFlowValue.instantiated()){
			return;// nothing to filter
		}
		buildSCC();
		for (int i = 0; i < n; i++) {
			ISet nei = g.getEnvelopGraph().getSuccessorsOf(i);
			for(int j=nei.getFirstElement();j>=0;j=nei.getNextElement()){
				if ((nodeSCC[i] != nodeSCC[j+n] && digraph.arcExists(j+n,i))
						||	(nodeSCC[i+n] != nodeSCC[j] && digraph.arcExists(i+n,j))){
					g.enforceArc(i,j,this);
				}else if (nodeSCC[i] != nodeSCC[j+n] && nodeSCC[i+n]!=nodeSCC[j]
						&&	!(digraph.arcExists(i+n,j)||digraph.arcExists(j+n,i))){
					g.removeArc(i,j,this);
					digraph.removeEdge(i,j+n);
					digraph.removeEdge(i+n,j);
				}
			}
		}
	}

	//***********************************************************************************
	// PROPAGATION
	//***********************************************************************************

	@Override
	public void propagate(int evtmask) throws ContradictionException {
		if(solver.getMeasures().getSolutionCount()==0){
			return;
		}
		if ((evtmask & EventType.FULL_PROPAGATION.mask) != 0) {
			buildDigraph();
		}
		repairMatching();
		filter();
		gdm.unfreeze();
	}

	@Override
	public void propagate(int varIdx, int mask) throws ContradictionException {
		if(solver.getMeasures().getSolutionCount()==0 || true){
			forcePropagate(EventType.FULL_PROPAGATION);
			return;
		}
		digraph.desactivateNode(n2);
		gdm.freeze();
		gdm.forEachArc(remProc, EventType.REMOVEARC);
		repairMatching();
		filter();
		gdm.unfreeze();
	}

	//***********************************************************************************
	// INFO
	//***********************************************************************************

	@Override
	public int getPropagationConditions(int vIdx) {
		return EventType.REMOVEARC.mask + EventType.INSTANTIATE.mask;
	}

	@Override
	public ESat isEntailed() {
		if(!(g.instantiated()&&maxFlowValue.instantiated()&&flowCost.instantiated())){
			return ESat.UNDEFINED;
		}
		throw new UnsupportedOperationException("Entailment check not implemented");
	}

	private class DirectedRemProc implements PairProcedure {
		public void execute(int i, int j) throws ContradictionException {
			removeDiArc(i+n,j);
			removeDiArc(j+n,i);
		}
		private void removeDiArc(int i, int j){
			digraph.removeArc(j,i);
			if(digraph.removeArc(i,j)){
				flow[i].add(-1);
				flow[j].add(-1);
				totalFlow.add(-1);
			}
		}
	}
}
