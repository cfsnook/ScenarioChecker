/*******************************************************************************
 *  Copyright (c) 2019-2020 University of Southampton.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *   
 *  Contributors:
 *  University of Southampton - Initial implementation
 *******************************************************************************/

package ac.soton.eventb.scenariochecker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.eventb.core.IMachineRoot;
import org.eventb.emf.core.machine.Event;
import org.eventb.emf.core.machine.Machine;
import org.eventb.emf.persistence.EMFRodinDB;

import ac.soton.eventb.emf.oracle.OracleFactory;
import ac.soton.eventb.emf.oracle.Run;
import ac.soton.eventb.emf.oracle.Snapshot;
import ac.soton.eventb.emf.oracle.Step;
import ac.soton.eventb.internal.scenariochecker.Clock;
import ac.soton.eventb.internal.scenariochecker.OracleHandler;
import ac.soton.eventb.internal.scenariochecker.Playback;
import ac.soton.eventb.internal.scenariochecker.Triplet;
import ac.soton.eventb.internal.scenariochecker.Utils;
import ac.soton.eventb.probsupport.AnimationManager;
import ac.soton.eventb.probsupport.data.History_;
import ac.soton.eventb.probsupport.data.Operation_;
import ac.soton.eventb.probsupport.data.State_;

/**
 * 
 * This is the Scenario Checker manager
 * 
 * @author cfsnook
 *
 */
public class ScenarioCheckerManager  {
	
	private static final String recordingName = "Scenario";
	private static final String SETUP = "SETUP_CONTEXT";
	private static final String INITIALISATION = "INITIALISATION";
	
	//the Singleton ScenarioCheckerManager instance
	private static ScenarioCheckerManager instance = null;

	public static ScenarioCheckerManager getDefault() {
		if (instance==null){
			instance = new  ScenarioCheckerManager();
		}
		return instance;
	}
	
	private ScenarioCheckerManager() {	//make constructor private
	}
	
	private IMachineRoot mchRoot = null;
	private Machine machine = null;
	private Clock clock = new Clock();	
	private Operation_ manuallySelectedOp = null;
	private List<Operation_> enabledOperations = null;
	private boolean dirty = false;
	private Playback playback = null;
	private static List<IScenarioCheckerView> scenarioCheckerViews = new ArrayList<IScenarioCheckerView>();
	
	/**
	 * add a scenario checker view to those registered to receive notifications from the Scenario Checker Manager
	 * 
	 * @param scenarioCheckerView
	 */
	public void addSimulationView(IScenarioCheckerView scenarioCheckerView) {
		scenarioCheckerViews.add(scenarioCheckerView);
	}
	/**
	 * remove a scenario checker view from those registered to receive notifications from the Scenario Checker Manager
	 * @param scenarioCheckerView
	 */
	public void removeSimulationControlPanel(IScenarioCheckerView scenarioCheckerView) {
		scenarioCheckerViews.remove(scenarioCheckerView);
	}
	
	/**
	 * Initialise the Scenario Checker Manager with a particular machine root
	 * @param mchRoot
	 */
	public void initialise(IMachineRoot mchRoot) {
		this.mchRoot = mchRoot;
		EMFRodinDB emfRodinDB = new EMFRodinDB();
		machine = (Machine) emfRodinDB.loadEventBComponent(mchRoot);
		//initialise oracle file handler
		OracleHandler.getOracle().initialise(recordingName, machine);
		//start the scenario checker views
		for (IScenarioCheckerView scenarioCheckerView : scenarioCheckerViews) {
			scenarioCheckerView.start();
		}		
		restart(mchRoot);
	}
	
	/**
	 * This (re)starts the scenario without affecting any playback settings etc.
	 * @param mchRoot
	 */
	public void restart(IMachineRoot mchRoot) {
		if (mchRoot!=this.mchRoot) return;
		clock.reset();
		updateModeIndicator();
		setDirty(false);
		//execute setup operation automatically
		if (inSetup()) {
			runSetup();
		}
	}

	/**
	 * stops the current simulation
	 * 
	 * @param mchRoot
	 */
	public void stop(IMachineRoot mchRoot) {
		if (mchRoot!=this.mchRoot) return;
		if (mchRoot.getCorrespondingResource() != this.mchRoot.getCorrespondingResource()) return;
		//stop the scenario checker views
		for (IScenarioCheckerView scenarioCheckerView : scenarioCheckerViews) {
			scenarioCheckerView.stop();
		}
		
		// clear state
		this.mchRoot = null;
		machine = null;
		clock.reset();
		manuallySelectedOp = null;
		setDirty(false);
	}
	
	/**
	 * Tests whether the Scenario Checker is open and ready to start... 
	 * .. i.e. whether there is an open scenario checker view  attached
	 * @return
	 */
	public boolean isOpen() {
		for (IScenarioCheckerView scenarioCheckerView : scenarioCheckerViews) {
			if (scenarioCheckerView.isReady()) return true;
		}
		return false;
	}
	
	
	////////// interface for scenario checker views to implement user commands/////////
	
	/**
	 * control panel selection changed.
	 * This is called by the scenarioCheckerViews when the user has selected an operation.
	 * If 'fire' is true, the new selection will be executed as a big step now
	 * 
	 * @param opName
	 * @param fireNow
	 */
	public void selectionChanged(String operationSignature, boolean fireNow) {
		for (Operation_ operation : AnimationManager.getEnabledOperations(mchRoot)) {
			if (operationSignature.equals(operation.inStringFormat())){
				manuallySelectedOp = operation; 
				if (fireNow) {
					bigStep();
				}
				break;
			}
		}
	}
	
	/**
	 * check whether running in playback mode or not
	 * 
	 * @return
	 */
	public boolean isPlayback() {
		return playback!=null && playback.isPlayback();
	}
	
	/**
	 * 	implements the big step behaviour where we fire the next operation and then run to completion of all internal operations
	 */
	public boolean bigStep() {	
		if (inSetup()) return false;	
		Operation_ op = pickNextOperation();
		//Animator animator = Animator.getAnimator();
		boolean progress = true;
		//execute at least one
		progress = executeOperation(op, false);
		//continue executing any internal operations
		List<Operation_> loop = new ArrayList<Operation_>(); //prevent infinite looping in case doesn't converge
		while (	progress && 
				(op = pickNextOperation())!=null &&
				!isExternal(op) &&
				!loop.contains(op)
				) {
			progress = executeOperation(op, false);
			loop.add(op);
		}
		updateModeIndicator();
		return progress;
	}
	
	/**
	 *  implements the small step behaviour where we fire one enabled external or internal operation
	 */
	public void singleStep(){
		Operation_ op = pickNextOperation();
		executeOperation(op, false);
		updateModeIndicator();
	}
	
	/**
	 *  implements the run behaviour where we take the selected number of big steps
	 * @param ticks
	 * @return
	 */
	// (when not in playback mode we stop when a non-deterministic choice is available)  <<<<<<<<<< DISABLED FOR NOW - WHICH IS BETTER?
	public String runForTicks(Integer ticks) {
		if (inSetup()) return "In Setup";	
		final int endTime = clock.getValueInt()+ticks;
		boolean progress = true;
		while (clock.getValueInt() < endTime && progress) {
				progress = bigStep();
		}
		if (!progress) {
			return "Run terminated due to lack of progress.";
		}
		return "ok";
	}

	/**
	 * restarts the animation.
	 * if in playback, the current scenario is replayed.
	 * if not, the animation will be reset in recording mode
	 * 
	 */
	public void restartPressed() {
		if (isPlayback()){
			playback.reset();
		}
		AnimationManager.restartAnimation(mchRoot);
	}
	
	/**
	 * saves the current scenario
	 */
	public void savePressed() {
		Run run = makeRun(recordingName, AnimationManager.getHistory(mchRoot));
		try {
			OracleHandler.getOracle().save(run);
		} catch (CoreException e) {
			e.printStackTrace();
		}
		setDirty(false);
	}
	
	/**
	 * starts playing back a scenario
	 * if already in playback, the current scenario is restarted,
	 * if not, the user can select an oracle file
	 */
	public void replayPressed() {
		if (isPlayback()){
			playback.reset();
		}else {
			playback = new Playback(OracleHandler.getOracle().getRun().getEntries());
		}
		AnimationManager.restartAnimation(mchRoot);
	}
	
	/**
	 * stops the current playback and switches to recording mode
	 * (without restarting - so a new scenario can continue from the played back one)
	 */
	public void stopPressed() {
		if (isPlayback()){
			playback=null;;
		}
		updateModeIndicator();
	}
	
	/**
	 * checks whether the scenario is dirty
	 * (i.e. a scenario has been manually played beyond initialisation, but not yet saved)
	 * @return
	 */
	public boolean isDirty() {
		return dirty;
	}
	
	
	//////////////////// ProB listener interface ////////////
	/**
	 * called by the Animation Listener for ProB when the state has changed
	 * Since the order of notifications of state changes can vary we use the history 
	 * and only update when the history contains information past the trace point of the last update
	 * The oracle is only updated for external operations.
	 * 
	 */
	
	public void currentStateChanged(IMachineRoot mchRoot) {
		if (mchRoot!=this.mchRoot) return;
		
		{//update state views
			List<Triplet <String, String, String>> result = new ArrayList<Triplet<String,String,String>>();
			Map<String, String> currentState = AnimationManager.getCurrentState(mchRoot).getAllValues();
			// if in playback check state matches oracle
			if (isPlayback() && playback.getCurrentSnapshot()!=null) {
				for (Map.Entry<String, String> value : playback.getCurrentSnapshot().getValues()){
						result.add(Triplet.of(value.getKey(), currentState.get(value.getKey()), value.getValue()));
				}
			}else {
				for (Map.Entry<String, String> value : currentState.entrySet()){
					result.add(Triplet.of(value.getKey(), value.getValue(), ""));
				}
			}
			for (IScenarioCheckerView scenarioCheckerView : scenarioCheckerViews) {
				scenarioCheckerView.updateState(result);
			}
		}
		
		{//update the enabled external events views
			enabledOperations = AnimationManager.getEnabledOperations(mchRoot);
			List<String> operationSignatures = new ArrayList<String>();
			for(Operation_ op: enabledOperations){
				if (isExternal(op)) {
					operationSignatures.add(op.inStringFormat());
				}
			}
			
			// find index of the next op in playback
			int selectedOp = -1;
			if (isPlayback() && playback.getNextOperation()!=null) {
				selectedOp = enabledOperations.indexOf(playback.getNextOperation());
			}

			// update operations tables in the scenario checker views
			for (IScenarioCheckerView scenarioCheckerView : scenarioCheckerViews) {
				scenarioCheckerView.updateEnabledOperations(operationSignatures, selectedOp);
			}
		}
	}		
		
	///////////////// private utilities to help with execution ///////////////////////////

	/**
	 * update the mode indicator on scenario checker views
	 * according to whether the scenario is in playback or recording
	 */
	private void updateModeIndicator() {
		if (isPlayback()){
			for (IScenarioCheckerView controlPanel : scenarioCheckerViews) {
				controlPanel.updateModeIndicator(Mode.PLAYBACK);
			}
		}else{
			for (IScenarioCheckerView controlPanel : scenarioCheckerViews) {
				controlPanel.updateModeIndicator(Mode.RECORDING);
			}
		}
	}
	
	/**
	 * sets the dirty flag and tells the scenario checker views to show as dirty or not
	 * @param dirty
	 */
	private void setDirty(boolean dirty) {
		this.dirty=dirty;
		for (IScenarioCheckerView controlPanel : scenarioCheckerViews) {
			controlPanel.updateDirtyStatus(dirty);;
		}
	}
	
	/**
	 * make a new Run from the given animation History_
	 * @param history
	 * @param run
	 */
	private Run makeRun(String name, History_ history) {
		Run run = OracleFactory.eINSTANCE.createRun();
		run.setName(name);
		Clock runclock = new Clock();
		Snapshot currentSnapshot = null;
		for (History_.HistoryItem_ hi : history.getAllItems() ) { 
			Operation_ op = hi.operation;
			//we only record external events
			if (op!=null) {
				if (isExternal(op) || SETUP.equals(op.getName())) {
					//create a new step entry in the recording
					Step step = makeStep(op, runclock.getValue());
					run.getEntries().add(step);
					//create a new empty snapshot in the recording
					currentSnapshot = makeSnapshot(runclock.getValue());
					run.getEntries().add(currentSnapshot);
					//update clock
					runclock.inc();
				}
				//add any changed state to the current snapshot (for internal as well as external events)
				//later state changes overwrite earlier ones so that we end up with the state at the end of the run
				//(if a variable changes and then reverts this is still counted as a change)
				State_ state = hi.state;
				for (Entry<String, String> entry : state.getAllValues().entrySet()) {
					if (!Utils.isPrivate(entry.getKey(), machine)){
						if (hasChanged(entry.getKey(),entry.getValue(), run)){
							if (currentSnapshot!=null) {
								currentSnapshot.getValues().put(entry.getKey(), entry.getValue());
							}
						}
					}
				}
			}
		}
		return run;
	}
	
	/**
	 * make an empty Snapshot
	 * 
	 * @return
	 */
	private Snapshot makeSnapshot(String tick) {
		Snapshot currentSnapshot;
		currentSnapshot = OracleFactory.eINSTANCE.createSnapshot();
		currentSnapshot.setClock(tick);
		currentSnapshot.setMachine(machine.getName());
		currentSnapshot.setResult(true);	//for now, all snapshots are result = true
		return currentSnapshot;
	}
	
	/**
	 * make a new Step from the given Operation_
	 * 
	 * @param op
	 * @return
	 */
	private Step makeStep(Operation_ op, String tick) {
		Step step = OracleFactory.eINSTANCE.createStep();
		step.setName(op.getName());
		step.getArgs().addAll(op.getArguments());
		step.setMachine(machine.getName());
		step.setClock(tick);
		return step;
	}
	
	/**
	 * check whether the context needs to be set up
	 */
	private boolean inSetup(){
		List<Operation_> enabledOperations = AnimationManager.getEnabledOperations(mchRoot);
		for (Operation_ op : enabledOperations){
			if ("SETUP_CONTEXT".equals(op.getName()) ){
				return true;
			}
		}
		return false;
	}

	/**
	 * run the context set-up operation if enabled
	 **/
	private boolean runSetup(){
		boolean ret = false;
		for (Operation_ op : AnimationManager.getEnabledOperations(mchRoot)){
			if (SETUP.equals(op.getName())){
				if (isPlayback() && SETUP.equals(playback.getNextStep().getName())){
					playback.consumeStep();
				}
				executeOperation(op,false);
				ret=true;
			}
		}
		return ret;
	}
		
	/**
	 * finds the next operation to be executed.
	 * when not in playback mode, it is manually (or randomly) selected from those that are enabled according to priority (internal first)
	 * when in playback mode, external events are given by the next operation in the playback being replayed,
	 * 
	 * @param animator
	 * @return
	 */
	private Operation_ pickNextOperation() {	
		Operation_  nextOp = manuallySelectedOp!=null && isEnabled(manuallySelectedOp) ?
							manuallySelectedOp :
							pickFrom(prioritise(AnimationManager.getEnabledOperations(mchRoot)));
		
		if (isPlayback() && isExternal(nextOp)){
			nextOp = playback.getNextOperation();
		}
		
		return nextOp;
	}

	/**
	 * checks whether a particular operation is currently enabled
	 * @param op
	 * @return
	 */
	private boolean isEnabled(Operation_ op) {
		List<Operation_> enabled = AnimationManager.getEnabledOperations(mchRoot);
		for (Operation_ eop : enabled) {
			if (eop!=null && op.inStringFormat().equals(eop.inStringFormat())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * selects an operation from the given list at random
	 */
	private static final Random random = new Random();
	private Operation_ pickFrom(List<Operation_> ops) {
		Operation_ op = ops.get(random.nextInt(ops.size()));
		return op;
	}
	
	/**
	 * filter the given list of operations so that it contains the subset with the highest eventPriorities
	 * 
	 * @param enabledOperations
	 * @return
	 */
	private List<Operation_> prioritise(List<Operation_> enabledOperations) {
		List<Operation_> filtered = new ArrayList<Operation_>();
		Integer current = Integer.MAX_VALUE;
		
		for (Operation_ op : enabledOperations) {
			Integer priority;
			Event ev = Utils.findEvent(op.getName(), machine);
			priority = ev==null? -1 : Utils.getPriority(ev);
			if (priority>current) continue; 	//ignore lesser (i.e. higher int) eventPriorities
			if (priority<current) {				//found a better eventPriorities
				filtered.clear();
				current=priority;
			}
			filtered.add(op);
		}
		return filtered;
	}

	/**
	 * Execute the given operation.
	 * If recording and the operation is not setup nor initialisation, the scenario becomes dirty.
	 * If in playback and the operation is the next step, the playback progresses
	 * If the operation is external, the clock is incremented
	 * 
	 * 
	 * @param animator
	 * @param operation
	 * @param silent
	 * @return
	 */
	private boolean executeOperation(Operation_ operation, boolean silent){
		if (operation==null) return false;
		System.out.println("executing operation : "+operation.getName()+" "+operation.getArguments() );
		
		if (!isPlayback() && 
				!SETUP.equals(operation.getName()) && 
				!INITIALISATION.equals(operation.getName()) ) {
			setDirty(true);
		}
		
		if (isPlayback() && 
				operation.equals(playback.getNextOperation())) {
			playback.consumeStep();;
		}
		
		if (isExternal(operation)) {
			clock.inc();
		}
		
		//must make sure everything else is updated BEFORE executing,
		// as ProB will immediately call the listeners to update the views
		AnimationManager.executeOperation(mchRoot, operation, silent);
		
		return true;
	}
	
	/**
	 * checks whether the given operation is external
	 * @return
	 */
	private boolean isExternal(Operation_ operation) {
		return Utils.isExternal(Utils.findEvent(operation.getName(), machine));
	}
	
	/**
	 * Checks whether the value of the named identifier has changed since the last time it was recorded in the recording.
	 * 
	 * @param name
	 * @param value
	 * @param run 
	 * @return
	 */
	private boolean hasChanged(String name, String value, Run run) {
		if (run == null) return true;
		EList<ac.soton.eventb.emf.oracle.Entry> entries = run.getEntries();		
		for (int i = entries.size()-1; i>=0 ; i = i-1){
			if (entries.get(i) instanceof Snapshot){
				EMap<String, String> snapshotValues = ((Snapshot) entries.get(i)).getValues();
				if (snapshotValues.containsKey(name)){
					if (value.equals(snapshotValues.get(name))){
						return false;
					}else{
						return true;
					}	
				}
			}
		}
		return true;
	}
	

}
