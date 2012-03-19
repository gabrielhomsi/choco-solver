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
package samples;

import choco.kernel.ResolutionPolicy;
import org.kohsuke.args4j.Option;
import org.slf4j.LoggerFactory;
import solver.Solver;
import solver.constraints.ConstraintFactory;
import solver.constraints.binary.Element;
import solver.constraints.nary.AllDifferent;
import solver.constraints.nary.MaxOfAList;
import solver.constraints.nary.Sum;
import solver.constraints.nary.cnf.ConjunctiveNormalForm;
import solver.constraints.nary.cnf.Literal;
import solver.constraints.nary.cnf.Node;
import solver.constraints.reified.ReifiedConstraint;
import solver.propagation.generator.*;
import solver.search.strategy.StrategyFactory;
import solver.variables.BoolVar;
import solver.variables.IntVar;
import solver.variables.VariableFactory;
import solver.variables.view.Views;

/**
 * <br/>
 *
 * @author Charles Prud'homme
 * @since 17/03/12
 */
public class OpenStacks extends AbstractProblem {

    @Option(name = "-d", aliases = "--data", usage = "Open stacks instance.", required = false)
    Data data = Data.small;


    int nc; // nb of customers
    int np; // nb of products

    int[][] orders; // which customer orders which product

    int[] norders; // nb of orders per customer

    IntVar[] scheds; // schedule of products

    IntVar[][] o; // orders fill after time t
    BoolVar[][] o2b;
    IntVar[] open; // schedule of products

    IntVar objective;


    public void setUp() {
        int k = 0;
        nc = data.data[k++];
        np = data.data[k++];
        orders = new int[nc][np];
        norders = new int[nc];
        for (int j = 0; j < nc; j++) {
            int s = 0;
            for (int i = 0; i < np; i++) {
                orders[j][i] = data.data[k++];
                s += orders[j][i];
            }
            norders[j] = s;
        }
    }

    @Override
    public void buildModel() {
        setUp();
        solver = new Solver("Open stacks");

        scheds = VariableFactory.enumeratedArray("s", np, 0, np - 1, solver);
        solver.post(new AllDifferent(scheds, solver));
        o = new IntVar[nc][np + 1];
        for (int i = 0; i < nc; i++) {
            o[i] = VariableFactory.enumeratedArray("o_" + i, np + 1, 0, norders[i], solver);
            // no order at t = 0
            solver.post(ConstraintFactory.eq(o[i][0], 0, solver));
        }
        for (int t = 1; t < np + 1; t++) {
            for (int i = 0; i < nc; i++) {
                // o[i,t] = o[i,t-1] + orders[i,s[t]] );
                IntVar value = VariableFactory.enumerated("val_" + i, 0, norders[i], solver);
                solver.post(new Element(value, orders[i], scheds[t - 1], 0, solver));
                solver.post(Sum.eq(new IntVar[]{o[i][t - 1], value}, o[i][t], solver));
            }
        }
        o2b = VariableFactory.boolMatrix("b", np, nc, solver);
        for (int i = 0; i < nc; i++) {
            for (int j = 1; j < np + 1; j++) {
                BoolVar[] btmp = VariableFactory.boolArray("bT", 2, solver);
                solver.post(new ReifiedConstraint(
                        btmp[0],
                        ConstraintFactory.lt(o[i][j - 1], Views.fixed(norders[i], solver), solver),
                        ConstraintFactory.geq(o[i][j - 1], Views.fixed(norders[i], solver), solver),
                        solver));
                solver.post(new ReifiedConstraint(
                        btmp[1],
                        ConstraintFactory.gt(o[i][j], Views.fixed(0, solver), solver),
                        ConstraintFactory.leq(o[i][j], Views.fixed(0, solver), solver),
                        solver));
                solver.post(new ConjunctiveNormalForm(
                        Node.ifOnlyIf(Literal.pos(o2b[j - 1][i]), Node.and(Literal.pos(btmp[0]), Literal.pos(btmp[1]))),
                        solver));
            }
        }
        open = VariableFactory.boundedArray("open", np, 0, nc + 1, solver);
        for (int i = 0; i < np; i++) {
            solver.post(Sum.eq(o2b[i], open[i], solver));
        }


        objective = VariableFactory.bounded("OBJ", 0, nc * np, solver);
        solver.post(new MaxOfAList(objective, open, solver));
    }

    @Override
    public void configureSearch() {
        solver.set(StrategyFactory.minDomMinVal(scheds, solver.getEnvironment()));
    }

    @Override
    public void configureEngine() {
       /* if (true) {
            solver.set(new Sort(
                    new SortDyn(EvtRecEvaluators.MinDomSize, SortDyn.Op.MAX, new PVar(scheds)),
                    new SortDyn(EvtRecEvaluators.MaxArityV, SortDyn.Op.MAX, new PVar(solver.getVars())),
                    new Queue(new PCoarse(solver.getCstrs()))));
        } else*/ {
            solver.set(new Sort(
                    new Queue(new PArc(scheds)),
                    new Queue(new PVar(solver.getVars())),
                    new Queue(new PCoarse(solver.getCstrs()))));
        }
    }

    @Override
    public void solve() {
        solver.getSearchLoop().getLimitsBox().setNodeLimit(200000);
        solver.findOptimalSolution(ResolutionPolicy.MINIMIZE, objective);
    }

    @Override
    public void prettyOut() {
        LoggerFactory.getLogger("bench").info("Open stacks problem");
        StringBuilder st = new StringBuilder();
        st.append("\t");
        for (int i = 0; i < nc; i++) {
            for (int j = 0; j < np; j++) {
                st.append(orders[i][j]).append(" ");
            }
            st.append("(").append(norders[i]).append(")\n\t");
        }
        st.append("\n\t");
        if (solver.isFeasible() == Boolean.TRUE) {
            for (int j = 0; j < np; j++) {
                st.append(scheds[j].getValue()).append(" ");
            }
            st.append("\n\n\t");
            for (int j = 0; j < np; j++) {
                for (int i = 0; i < nc; i++) {
                    st.append(o2b[j][i].getValue()).append(" ");
                }
                st.append(" ").append(open[j].getValue()).append("\n\t");
            }

            st.append("\n\t").append("OBJ:").append(objective.getValue());
        } else {
            st.append("INFEASIBLE");
        }
        //st.append(solver.toString());
        LoggerFactory.getLogger("bench").info(st.toString());
    }

    public static void main(String[] args) {
        new OpenStacks().execute(args);
    }

    ////////////////////////////////////////// DATA ////////////////////////////////////////////////////////////////////
    static enum Data {
        V_small(new int[]{
                5, 6, //nb customers= 10, nb products = 20,
                // orders
                0, 0, 1, 0, 1, 0,
                0, 1, 0, 0, 0, 0,
                1, 0, 1, 1, 0, 0,
                1, 1, 0, 0, 0, 1,
                0, 0, 0, 1, 1, 1
        }),
        small(new int[]{
                10, 20, //nb customers= 10, nb products = 20,
                // orders
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 1,
                1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1,
                0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0, 1, 0, 1, 1, 0, 0,
                0, 1, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0,
                0, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0, 1, 0,
                0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0,
                0, 1, 0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0,
                0, 0, 1, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0,
                1, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0,
        });
        final int[] data;

        Data(int[] data) {
            this.data = data;
        }

        public int get(int i) {
            return data[i];
        }
    }
}
