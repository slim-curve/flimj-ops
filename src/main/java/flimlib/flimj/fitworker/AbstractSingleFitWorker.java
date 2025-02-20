/*-
 * #%L
 * Fluorescence lifetime analysis in ImageJ.
 * %%
 * Copyright (C) 2017 - 2022 Board of Regents of the University of Wisconsin-Madison.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package flimlib.flimj.fitworker;

import java.util.List;

import net.imagej.ops.OpEnvironment;
import net.imagej.ops.thread.chunker.ChunkerOp;
import net.imagej.ops.thread.chunker.CursorBasedChunk;
import flimlib.flimj.FitParams;
import flimlib.flimj.FitResults;
import flimlib.flimj.utils.RAHelper;
import net.imglib2.type.numeric.RealType;

public abstract class AbstractSingleFitWorker<I extends RealType<I>> extends AbstractFitWorker<I> {

	/** Data buffers, all except for {@code transBuffer} are writable */
	protected final float[] paramBuffer, transBuffer, chisqBuffer, fittedBuffer, residualBuffer;

	public AbstractSingleFitWorker(FitParams<I> params, FitResults results, OpEnvironment ops) {
		super(params, results, ops);

		// setup input buffers
		if (results.param == null || results.param.length != nParam) {
			results.param = new float[nParam];
		}
		if (params.trans == null || params.trans.length != nDataTotal) {
			params.trans = new float[nDataTotal];
		}
		paramBuffer = results.param;
		transBuffer = params.trans;

		// setup output buffers
		chisqBuffer = new float[1];
		if (results.fitted == null || results.fitted.length != nDataTotal) {
			results.fitted = new float[nDataTotal];
		}
		if (results.residuals == null || results.residuals.length != nDataTotal) {
			results.residuals = new float[nDataTotal];
		}
		fittedBuffer = results.fitted;
		residualBuffer = results.residuals;
	}

	/**
	 * A routine called before {@link #doFit()}. Can be used to throw away the left-overs from the
	 * previous run.
	 */
	protected void beforeFit() {
		chisqBuffer[0] = -1;
	}

	/**
	 * Does the actual implementation-specific fitting routine.
	 */
	protected abstract void doFit();

	/**
	 * A routine called after {@link #doFit()}. Can be used to copy back results from buffers.
	 */
	protected void afterFit() {
		// reduced by degree of freedom
		results.chisq = chisqBuffer[0] / (nData - nParam);
	}

	/**
	 * Fit the data in the buffer.
	 */
	protected void fitSingle() {
		beforeFit();

		doFit();

		afterFit();
	}

	/**
	 * Make a worker of the same kind but does not share any writable buffers (thread safe) if that
	 * buffer is null.
	 * 
	 * @param params the parameters
	 * @param rslts  the results
	 * @return a worker of the same kind.
	 */
	protected abstract AbstractSingleFitWorker<I> duplicate(FitParams<I> params, FitResults rslts);

	/**
	 * Called on the worker thread after worker duplication. Can be used to initialize thread-local
	 * globals such as Bayesian search grid parameters.
	 * 
	 * @see BayesFitWorker#onThreadInit()
	 */
	protected void onThreadInit() {}

	@Override
	public void fitBatch(List<int[]> pos, FitEventHandler<I> handler) {
		final AbstractSingleFitWorker<I> thisWorker = this;
		ops.run(ChunkerOp.class, new CursorBasedChunk() {

			@Override
			public void execute(long startIndex, long stepSize, long numSteps) {
				if (!params.multithread) {
					// let the first fitting thread do all the work
					if (startIndex != 0) {
						return;
					}
					numSteps = pos.size();
				}

				// thread-local reusable read/write buffers
				final FitParams<I> lParams;
				final FitResults lResults;
				final AbstractSingleFitWorker<I> fitWorker;
				// don't make copy in single thread mode
				if (!params.multithread || pos.size() == 1) {
					lParams = params;
					lResults = results;
					fitWorker = thisWorker;
				} else {
					lParams = params.copy();
					lResults = results.copy();
					// grab your own buffer
					lParams.param = lParams.trans =
							lResults.param = lResults.fitted = lResults.residuals = null;
					fitWorker = duplicate(lParams, lResults);
				}
				fitWorker.onThreadInit();

				final RAHelper<I> helper = new RAHelper<>(params, results);

				for (int i = (int) startIndex; i < startIndex + numSteps; i += stepSize) {
					final int[] xytPos = pos.get(i);

					if (!helper.loadData(fitWorker.transBuffer, fitWorker.paramBuffer, params, xytPos))
						lResults.retCode = FitResults.RET_INTENSITY_BELOW_THRESH;
					else {
						fitWorker.fitSingle();

						// invalidate fit if chisq is insane
						final float chisq = lResults.chisq;
						if (params.dropBad && lResults.retCode == FitResults.RET_OK
								&& (chisq < 0 || chisq > 1E5 || Float.isNaN(chisq)))
							lResults.retCode = FitResults.RET_BAD_FIT_CHISQ_OUT_OF_RANGE;
					}

					helper.commitRslts(lParams, lResults, xytPos);

					if (handler != null)
						handler.onSingleComplete(xytPos, params, results);
				}
			}

		}, pos.size());

		if (handler != null)
			handler.onComplete(params, results);
	}
}
