package scheduler;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.mortbay.util.MultiMap;

import backtype.storm.generated.Bolt;
import backtype.storm.generated.GlobalStreamId;
import backtype.storm.generated.SpoutSpec;
import backtype.storm.generated.StormTopology;
import backtype.storm.scheduler.Cluster;
import backtype.storm.scheduler.EvenScheduler;
import backtype.storm.scheduler.ExecutorDetails;
import backtype.storm.scheduler.IScheduler;
import backtype.storm.scheduler.SupervisorDetails;
import backtype.storm.scheduler.Topologies;
import backtype.storm.scheduler.TopologyDetails;
import backtype.storm.scheduler.WorkerSlot;

public class LocalGlobalGroupScheduler implements IScheduler {
	
	Random rand = new Random(System.currentTimeMillis());
	JSONParser parser = new JSONParser();
	CloudLocator clocator;
	Map storm_config;
	
	final String ackerBolt = "__acker";
	final String CONF_sourceCloudKey = "geoScheduler.sourceCloudList";
	final String CONF_cloudLocatorKey = "geoScheduler.cloudInformation";
	
	String taskGroupListFile = "/home/kend/fromSICSCloud/Scheduler-GroupList.txt";
	String schedulerResultFile = "/home/kend/SchedulerResult.csv";
	String pairSupervisorTaskFile = "/home/kend/fromSICSCloud/PairSupervisorTasks.txt";
	
    public void prepare(Map conf) 
    {
    	//Retrieve data from storm.yaml config file 
    	storm_config = conf;
    }

    @SuppressWarnings("unchecked")
	public void schedule(Topologies topologies, Cluster cluster) {
    	
	    System.out.println("NetworkAwareGroupScheduler: begin scheduling");
	    
	    HashMap<String,String[]> spoutCloudsPair = new HashMap<String, String[]>();
	    LinkedHashMap<String, LocalTask> localTaskList = new LinkedHashMap<String,LocalTask>();
	    LinkedHashMap<String, GlobalTask> globalTaskList = new LinkedHashMap<String,GlobalTask>();
	    
	    try {
	    	
	    	//Reading the information from file
	    	String sourceCloudTaskFile = storm_config.get(CONF_sourceCloudKey).toString();
	    	System.out.println("Path for sourceCloudTaskFile : " + sourceCloudTaskFile);
	    	
		    spoutLocationFileReader(sourceCloudTaskFile, spoutCloudsPair);
		    //taskGroupListFileReader(taskGroupListFile, localTaskList, globalTaskList);
		    
	    }catch(Exception e){
	    	System.out.println("Some exception happened when reading the file : \n " + e.getMessage());
	    	e.printStackTrace();
	    	return;
	    	}
	    
	    if(spoutCloudsPair.size() == 0 /*|| globalGroupNameList.size() == 0*/)
        {
        	System.out.println("Reading is not complete, stop scheduling for now");
        	return;
        }
	    
	    System.out.println("Start categorizing the supervisor");
	    Collection<SupervisorDetails> supervisors = cluster.getSupervisors().values();
	    
	    MultiMap supervisorsByCloudName = new MultiMap();
        MultiMap workersByCloudName = new MultiMap();
        MultiMap tasksByCloudName = new MultiMap();
	    HashMap<String, Cloud> clouds = new HashMap<String, Cloud>();
        
        //map the supervisors and workers based on cloud names
        for (SupervisorDetails supervisor : supervisors) {
        	Map<String, Object> metadata = (Map<String, Object>)supervisor.getSchedulerMeta();
        	if(metadata.get("cloud-name") != null){
        		Cloud c;
        		if(clouds.containsKey(metadata.get("cloud-name")))
        			c = clouds.get(metadata.get("cloud-name").toString());
        		else
        			c = new Cloud(metadata.get("cloud-name").toString());
        		
        		c.addSupervisor(supervisor);
        		c.addWorkers(cluster.getAvailableSlots(supervisor));
        		supervisorsByCloudName.add(metadata.get("cloud-name"), supervisor);
        		workersByCloudName.addValues(metadata.get("cloud-name"), cluster.getAvailableSlots(supervisor));
        	}
        }
        
        //print the worker list
        for(Cloud C : clouds.values())
        {
        	System.out.println(C.name + " :");
        	//System.out.println("Supervisors: " + C.getSupervisors());
        	System.out.println("Workers: " + C.getWorkers());
        	System.out.println("");
        }
        
        String clocatorFile = storm_config.get(CONF_cloudLocatorKey).toString();
        System.out.println("Path for clocatorFile : " + clocatorFile);
        clocator = new CloudLocator(clocatorFile);
        
        
		for (TopologyDetails topology : topologies.getTopologies()) {
			
			if (!cluster.needsScheduling(topology) || cluster.getNeedsSchedulingComponentToExecutors(topology).isEmpty()) {
	            		System.out.println("This topology doesn't need scheduling.");
			}
			else {
				
				MultiMap executorWorkerMap = new MultiMap();
				MultiMap executorCloudMap = new MultiMap();
				
				StormTopology st = topology.getTopology();
				Map<String, Bolt> bolts = st.get_bolts();
				Map<String, SpoutSpec> spouts = st.get_spouts();

				System.out.println("LOG: Categorizing Spouts into TaskGroup");
				for(String name : spouts.keySet()){
                    
					SpoutSpec spoutSpec = spouts.get(name);
                    
					try {
						JSONObject conf = (JSONObject)parser.parse(spoutSpec.get_common().get_json_conf());
						
						if(conf.get("group-name") != null){
							String groupName = (String)conf.get("group-name");
							LocalTask schedulergroup = null;
							
							//Spout only reside in LocalTask
							if(localTaskList.containsKey(groupName))
								schedulergroup = localTaskList.get(groupName);
							else
							{
								//Create new LocalTaskGroup
								LocalTask newTaskGroup =  new LocalTask(groupName);
								localTaskList.put(groupName,newTaskGroup);
								schedulergroup = newTaskGroup;
							}
							
							if(schedulergroup != null)
							{
								schedulergroup.spoutsWithParInfo.put(name, spoutSpec.get_common().get_parallelism_hint());
								for(String cloudName : spoutCloudsPair.get(name))
									schedulergroup.clouds.add(cloudName);
							}
							else
							{
								System.out.println("ERROR: " + name + " don't have any valid group. This task will be ignored in scheduling");
							}
						}
						
					}catch(ParseException e){e.printStackTrace();}
				}
				
				System.out.println("LOG: Categorizing Bolts into TaskGroup");
				for(String name : bolts.keySet()){
					System.out.println(name);
					
                    Bolt b = bolts.get(name);
                    Set<GlobalStreamId> inputStreams = b.get_common().get_inputs().keySet();
                    
					try {
						JSONObject conf = (JSONObject)parser.parse(b.get_common().get_json_conf());
						
						if(conf.get("group-name") != null){
							String groupName = (String)conf.get("group-name");
							TaskGroup schedulergroup = null;
							
							//each task only reside in one group
							//Check list of localTask first, then globalTask
							if(localTaskList.containsKey(groupName))
								schedulergroup = localTaskList.get(groupName);
							else if(globalTaskList.containsKey(groupName))
								schedulergroup = globalTaskList.get(groupName);
							else
							{
								//Create new GlobalTaskGroup
								GlobalTask newTaskGroup =  new GlobalTask(groupName);
								globalTaskList.put(groupName,newTaskGroup);
								schedulergroup = newTaskGroup;
							}
							
							if(schedulergroup != null)
							{
								schedulergroup.boltsWithParInfo.put(name, b.get_common().get_parallelism_hint());
								
								for(GlobalStreamId streamId : inputStreams)
								{
									System.out.println("--dependent to " + streamId.get_componentId());
									schedulergroup.boltDependencies.add(streamId.get_componentId());
								}
							}
							else
							{
								System.out.println("ERROR: " + name + " don't have any valid TaskGroup. This task will be ignored in scheduling");
							}
						}
						
					}catch(ParseException e){e.printStackTrace();}
				}
				
				
				Map<String, List<ExecutorDetails>> componentToExecutors = cluster.getNeedsSchedulingComponentToExecutors(topology);
				String needScheduling = "needs scheduling(component->executor): " + componentToExecutors;
				System.out.println(needScheduling);

				//Local group task distribution
				//for each spouts:
				//get clouds 
				for(LocalTask localGroup : localTaskList.values())
				{
					try 
					{
						System.out.println("LOG: " + localGroup.name + "distribution");
						
						for(Map.Entry<String,Integer> spout : localGroup.spoutsWithParInfo.entrySet())
						{
							System.out.println("-" + spout.getKey() + ": " + spout.getValue());
							List<ExecutorDetails> executors = componentToExecutors.get(spout.getKey());
							
							String spoutName = spout.getKey();
							int spoutParHint = spout.getValue();
							
							if(executors == null || executors.isEmpty())
			            		System.out.println(localGroup.name + ": " + spoutName + ": No executors");
			            	else
			            	{
			            		int cloudIndex = 0;
			            		int executorPerCloud = spoutParHint / localGroup.clouds.size(); //for now, only work on even number between executors to workers
			            		for(String cloudName : localGroup.clouds)
			            		{			            			
			            			int startidx = cloudIndex * executorPerCloud;
			            			int endidx = startidx + executorPerCloud;
			            			
			            			if (endidx > executors.size())
			            				endidx = executors.size() - 1;
			            			
			            			List<ExecutorDetails> subexecutors = executors.subList(startidx, endidx);
			            			List<WorkerSlot> workers = (List<WorkerSlot>) workersByCloudName.get(cloudName);
			            			
			            			System.out.println("---" + cloudName + "\n" + "-----subexecutors:" + subexecutors);
			            			
			            			if(workers == null || workers.isEmpty())
			    	            		System.out.println(localGroup.name + ": " + cloudName + ": No workers");
			            			else
			            			{
			            				deployExecutorToWorkers(workers, subexecutors, executorWorkerMap);
			            				executorCloudMap.add(spoutName, cloudName);
			            				
			            				for(ExecutorDetails ex : subexecutors)
		            	        			tasksByCloudName.add(cloudName, ex.getStartTask());
			            			}
			            			
			            			cloudIndex++;
			            		}
			            	}
						}
						
						for(String bolt : localGroup.boltsWithParInfo.keySet())
						{
							System.out.println("-" + bolt);
							List<ExecutorDetails> executors = componentToExecutors.get(bolt);
							int parHint = localGroup.boltsWithParInfo.get(bolt);
							
							if(executors == null || executors.isEmpty())
			            	{
			            		System.out.println(localGroup.name + ": " + bolt + ": No executors");
			            	}
			            	else
			            	{
			            		int cloudIndex = 0;
			            		int exPerCloud = parHint / localGroup.clouds.size(); //to be safe, only work on even number between executors to workers
			            		for(String cloudName : localGroup.clouds)
			            		{
			            			int startidx = cloudIndex * exPerCloud;
			            			int endidx = startidx + exPerCloud;
			            			
			            			if (endidx > executors.size())
			            				endidx = executors.size() - 1;
			            			
			            			List<ExecutorDetails> subexecutors = executors.subList(startidx, endidx);
			            			List<WorkerSlot> workers = (List<WorkerSlot>) workersByCloudName.get(cloudName);
			            			
			            			System.out.println("---" + cloudName + "\n" + "-----subexecutors:" + subexecutors);
			            			
			            			if(workers == null || workers.isEmpty())
			    	            		System.out.println(localGroup.name + ": " + cloudName + ": No workers");
			            			else
			            			{
			            				deployExecutorToWorkers(workers, subexecutors, executorWorkerMap);
			            				executorCloudMap.add(bolt, cloudName);
			            				
			            				for(ExecutorDetails ex : subexecutors)
			            	        			tasksByCloudName.add(cloudName, ex.getStartTask());			            				
			            			}
			            			
			            			cloudIndex++;
			            		}
			            	}
						}
					} catch(Exception e) 
						{System.out.println(e);}
				}
				
				
				//Global group task distribution
				for(TaskGroup globalTask : globalTaskList.values())
				{
					try {
						System.out.println("LOG: " + globalTask.name + " distribution");
						
						Set<String> cloudDependencies = new HashSet<String>();
						for(String dependentExecutors : globalTask.boltDependencies)
						{
							if(executorCloudMap.getValues(dependentExecutors) == null)
								continue;
							else
								cloudDependencies.addAll((List<String>) executorCloudMap.getValues(dependentExecutors));
						}
						
						
						Set<String> cloudSet = supervisorsByCloudName.keySet();
						System.out.println("-cloudDependencies: " + cloudDependencies);
						clocator.update(clocatorFile);
						String choosenCloud = clocator.getCloudBasedOnLatency(CloudLocator.Type.MinMax, cloudSet, cloudDependencies);
						//String choosenCloud = "CloudMidA"; //hardcoded
						
						globalTask.clouds.add(choosenCloud);
						
						System.out.println("-choosenCloud: " + choosenCloud);
						
						if(choosenCloud == null)
							System.out.println("WARNING! no cloud chosen for this group!");
						
						for(String bolt : globalTask.boltsWithParInfo.keySet())
						{
							System.out.println("---" + bolt);
							List<ExecutorDetails> executors = new ArrayList<ExecutorDetails>();
							executors.addAll(componentToExecutors.get(bolt));
							
							List<WorkerSlot> workersInCloud = (List<WorkerSlot>) workersByCloudName.get(choosenCloud);
							
							System.out.println("-----subexecutors:" + executors);
	            			System.out.println("-----workers:" + workersInCloud);
							
							if(executors.size() == 0)
			            		System.out.println(globalTask.name + ": " + bolt + ": No executors");
			            	else if(workersInCloud.isEmpty())
	    	            		System.out.println(globalTask.name + ": " + choosenCloud + ": No workers");
		            		else {
	            				deployExecutorToWorkers(workersInCloud, executors, executorWorkerMap);
	            				executorCloudMap.add(bolt, choosenCloud);
	            				
	            				for(ExecutorDetails ex : executors)
	            	        			tasksByCloudName.add(choosenCloud, ex.getStartTask());	            				
		            		}
						}
						
						
						//Addition for ackers
						//put all of the ack-bolts to the GlobalTask Cloud
						List<ExecutorDetails> ackers = new ArrayList<ExecutorDetails>();
						ackers.addAll(componentToExecutors.get(ackerBolt));
						List<WorkerSlot> workerAckers = (List<WorkerSlot>) workersByCloudName.get(choosenCloud);
						
						if(!ackers.isEmpty())
						{
							deployExecutorToWorkers(workerAckers, ackers, executorWorkerMap);
        				
							for(ExecutorDetails ex : ackers)
								tasksByCloudName.add(choosenCloud, ex.getStartTask());
						
						}
						//Finish addition for ackers
					
						
					} catch(Exception e) {
						System.out.println(e);
						}
				}

	            //Assign the tasks into cluster
	            StringBuilder schedulerResultStringBuilder = new StringBuilder();
	            schedulerResultStringBuilder.append("-----------------------------------\n");
	            schedulerResultStringBuilder.append("Task number to worker location\n");
	            schedulerResultStringBuilder.append("-----------------------------------\n\n");
	            schedulerResultStringBuilder.append(needScheduling + "\n");
	            for(Object ws : executorWorkerMap.keySet())
	        	{
	            	List<ExecutorDetails> edetails = (List<ExecutorDetails>) executorWorkerMap.getValues(ws);
	            	WorkerSlot wslot = (WorkerSlot) ws;
	            	
	        		cluster.assign(wslot, topology.getId(), edetails);
	        		System.out.println("We assigned executors:" + executorWorkerMap.getValues(ws) + " to slot: [" + wslot.getNodeId() + ", " + wslot.getPort() + "]");
	        		schedulerResultStringBuilder.append(executorWorkerMap.getValues(ws) + " to slot: [" + wslot.getNodeId() + ", " + wslot.getPort() + "]\n");
	        	}
	            
	            //Printing the TaskGroup and their cloud location
	            schedulerResultStringBuilder.append("\n\n-----------------------------------\n");
	            schedulerResultStringBuilder.append("TaskGroup and Cloud locations\n");
	            schedulerResultStringBuilder.append("-----------------------------------\n\n");
	            
				for(TaskGroup Group : localTaskList.values())
					schedulerResultStringBuilder.append(Group.name + ": " + Group.clouds + "\n");
				for(TaskGroup Group : globalTaskList.values())
					schedulerResultStringBuilder.append(Group.name + ": " + Group.clouds + "\n");
				
				try {
					FileWriter writer = new FileWriter(schedulerResultFile, true);
					writer.write(schedulerResultStringBuilder.toString());
					writer.close();
				}catch(Exception e){ }
				
				//Create a file pair of CloudName and tasks assigned to this cloud
				//This file is needed for zoneGrouping
				//format: 
				printTaskCloudPairs(tasksByCloudName,pairSupervisorTaskFile);
	        }
	    }
	        
	        // let system's even scheduler handle the rest scheduling work
	        // you can also use your own other scheduler here, this is what
	        // makes storm's scheduler composable.
	        new EvenScheduler().schedule(topologies, cluster);
    }

	private void spoutLocationFileReader(String sourceCloudTaskFile,
			HashMap<String, String[]> spoutCloudsPair)
			throws FileNotFoundException, IOException {
		FileReader pairDataFile;
		BufferedReader textReader;
		String line;
		pairDataFile = new FileReader(sourceCloudTaskFile);
		textReader  = new BufferedReader(pairDataFile);
		
		line = textReader.readLine();
		while(line != null && !line.equals(""))
		{
			//Format
			//SpoutID;cloudA,cloudB,cloudC
			System.out.println("Read from file: " + line);
			String[] pairString = line.split(";");
			String[] cloudList = pairString[1].split(",");
			spoutCloudsPair.put(pairString[0],cloudList);
			
			line = textReader.readLine();
		}
		textReader.close();
	}
    
	private void taskGroupListFileReader(
			String taskGroupFile,
			LinkedHashMap<String, LocalTask> localGroupNameList,
			LinkedHashMap<String, GlobalTask> globalGroupNameList)
			throws FileNotFoundException, IOException {
		FileReader pairDataFile;
		BufferedReader textReader;
		String line;
		pairDataFile = new FileReader(taskGroupFile);
		textReader  = new BufferedReader(pairDataFile);
		
		line = textReader.readLine();
		while(line != null && !line.equals(""))
		{
			//Format
			//Global1;Global / Local1;Local
			System.out.println("Read from file: " + line);
			String[] pairString = line.split(";");
			if(pairString[1].contains("Local"))
				localGroupNameList.put(pairString[0],new LocalTask(pairString[0]));
			else if(pairString[1].contains("Global"))
				globalGroupNameList.put(pairString[0],new GlobalTask(pairString[0]));

			line = textReader.readLine();
		}
		textReader.close();
	}

	private void deployExecutorToWorkers(List<WorkerSlot> cloudWorkers, List<ExecutorDetails> executors, MultiMap executorWorkerMap)
    {
    	Iterator<WorkerSlot> workerIterator = cloudWorkers.iterator();
    	Iterator<ExecutorDetails> executorIterator = executors.iterator();

    	//if executors >= workers, do simple round robin
    	//for all executors A to all supervisors B
    	if(executors.size() >= cloudWorkers.size())
    	{
        	while(executorIterator.hasNext() && workerIterator.hasNext())
        	{
        		WorkerSlot w = workerIterator.next();
        		ExecutorDetails ed = executorIterator.next();
        		executorWorkerMap.add(w, ed);
        		
        		//Reset to first worker again
        		if(!workerIterator.hasNext())
        			workerIterator = cloudWorkers.iterator();
        	}
    	}
    	
    	//if workers > executors, choose randomly
    	//for all executors A to all supervisors B
    	else
    	{
        	while(executorIterator.hasNext() && !cloudWorkers.isEmpty())
        	{
        		WorkerSlot w = cloudWorkers.get(rand.nextInt(cloudWorkers.size()));
        		ExecutorDetails ed = executorIterator.next();
        		executorWorkerMap.add(w, ed);
        	}
    	}
    }

	@SuppressWarnings("unchecked")
	private void printTaskCloudPairs(MultiMap tasksByCloudName, String fileLocation)
	{
		System.out.println("tasksByCloudName: " + tasksByCloudName.size());
		
		StringBuilder taskStringBuilder = new StringBuilder();            
		for(Object sup : tasksByCloudName.keySet())
		{
			String taskString = (String) sup + ";";
		
			for(Integer t : (List<Integer>) tasksByCloudName.getValues(sup))
			{
				taskString = taskString + t.toString() + ",";
			}
		
			taskStringBuilder.append(taskString.substring(0, taskString.length()-1));
			taskStringBuilder.append("\n");
			
			System.out.println(taskStringBuilder.toString());
		}
         
        try 
        {
			FileWriter writer = new FileWriter(fileLocation, true);
			writer.write(taskStringBuilder.toString());
			writer.close();
		} catch(Exception e){ }
	}
}

class TaskGroup {
	public TaskGroup(String Groupname) {
		name = Groupname;
	}
	
	public String name;
	public List<String> clouds = new ArrayList<String>();
	public LinkedHashMap<String,Integer> boltsWithParInfo = new LinkedHashMap<String, Integer>();
	public Set<String> boltDependencies = new HashSet<String>();
}

class LocalTask extends TaskGroup {
	public LocalTask(String Groupname) {
		super(Groupname);
	}
	
	public LinkedHashMap<String,Integer> spoutsWithParInfo = new LinkedHashMap<String, Integer>();
}

class GlobalTask extends TaskGroup {
	public GlobalTask(String Groupname) {
		super(Groupname);
	}
	
	
}

class Cloud {
	String name;
	List<SupervisorDetails> supervisors;
	List<WorkerSlot> workers;
	List<Integer> tasks;
	
	public Cloud(String n)
	{
		name = n;
		
		supervisors = new ArrayList<SupervisorDetails>();
		workers = new ArrayList<WorkerSlot>();
		tasks = new ArrayList<Integer>();
	}

	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * @return the supervisors
	 */
	public List<SupervisorDetails> getSupervisors() {
		return supervisors;
	}
	
	public void addSupervisor(SupervisorDetails sup)
	{
		supervisors.add(sup);
	}

	/**
	 * @return the workers
	 */
	public List<WorkerSlot> getWorkers() {
		return workers;
	}
	
	public void addWorker(WorkerSlot worker) {
		workers.add(worker);
	}
	
	public void addWorkers(List<WorkerSlot> works) {
		workers.addAll(works);
	}

	/**
	 * @return the tasks
	 */
	public List<Integer> getTasks() {
		return tasks;
	}
	
}