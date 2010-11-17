/**
 * 
 */
package edu.brown.designer.partitioners;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;

import org.apache.commons.collections15.map.ListOrderedMap;
import org.apache.commons.collections15.set.ListOrderedSet;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.voltdb.catalog.*;
import org.voltdb.utils.Pair;

import edu.brown.catalog.CatalogKey;
import edu.brown.catalog.CatalogUtil;
import edu.brown.catalog.special.MultiColumn;
import edu.brown.catalog.special.MultiProcParameter;
import edu.brown.catalog.special.NullProcParameter;
import edu.brown.catalog.special.ReplicatedColumn;
import edu.brown.correlations.Correlation;
import edu.brown.correlations.ParameterCorrelations;
import edu.brown.costmodel.AbstractCostModel;
import edu.brown.designer.AccessGraph;
import edu.brown.designer.Designer;
import edu.brown.designer.DesignerHints;
import edu.brown.designer.DesignerInfo;
import edu.brown.rand.RandomDistribution;
import edu.brown.statistics.Histogram;
import edu.brown.statistics.TableStatistics;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.JSONSerializable;
import edu.brown.utils.JSONUtil;
import edu.brown.utils.MathUtil;
import edu.brown.utils.StringUtil;

/**
 * @author pavlo
 *
 */
public class LNSPartitioner extends AbstractPartitioner implements JSONSerializable {
    protected static final Logger LOG = Logger.getLogger(LNSPartitioner.class);

    protected static final double RELAXATION_FACTOR_MIN = 0.25; 
    protected static final double RELAXATION_FACTOR_MAX = 0.50;
    protected static final double RELAXATION_MIN_SIZE = 5;

    enum Members {
        INITIAL_SOLUTION,
        INITIAL_COST,
        INITIAL_MEMORY,
        BEST_SOLUTION,
        BEST_COST,
        BEST_MEMORY,
        LAST_EXHAUSTED_SEARCH,
        LAST_RELAX_SIZE,
        RESTART_CTR,
        START_TIME,
        LAST_CHECKPOINT,
    };
    
    
    protected final Random rng = new Random();
    protected final AbstractCostModel costmodel;
    protected final ParameterCorrelations correlations;
    protected AccessGraph agraph;

    // ----------------------------------------------------------------------------
    // STATE INFORMATION
    // ----------------------------------------------------------------------------
    protected transient boolean init_called = false;
    
    public PartitionPlan initial_solution;
    public double initial_cost;
    public double initial_memory;
    
    public PartitionPlan best_solution;
    public double best_cost;
    public double best_memory;

    public Long start_time = null;
    public Long last_checkpoint = null;
    public boolean last_exhausted_search = false;
    public int last_relax_size = 0;
    public Integer restart_ctr = null;
    
    // ----------------------------------------------------------------------------
    // PRE-COMPUTED CATALOG INFORMATION
    // ----------------------------------------------------------------------------
    protected final ListOrderedMap<Table, ListOrderedSet<Column>> orig_table_attributes = new ListOrderedMap<Table, ListOrderedSet<Column>>();
    protected final Map<Table, Long> table_nonreplicated_size = new HashMap<Table, Long>();
    protected final Map<Table, Long> table_replicated_size = new HashMap<Table, Long>();
    protected final Map<Table, Set<Procedure>> table_procedures = new HashMap<Table, Set<Procedure>>();
    
    protected final Map<Column, Set<Procedure>> column_procedures = new HashMap<Column, Set<Procedure>>();
    protected final Map<Column, Map<Column, Set<Procedure>>> columnswap_procedures = new HashMap<Column, Map<Column,Set<Procedure>>>();

    protected final ListOrderedMap<Procedure, ListOrderedSet<ProcParameter>> orig_proc_attributes = new ListOrderedMap<Procedure, ListOrderedSet<ProcParameter>>();
    protected final Map<Procedure, Set<Column>> proc_columns = new HashMap<Procedure, Set<Column>>();
    protected final Map<Procedure, Histogram> proc_column_histogram = new HashMap<Procedure, Histogram>();
    protected final Map<Procedure, Map<ProcParameter, Set<MultiProcParameter>>> proc_multipoc_map = new HashMap<Procedure, Map<ProcParameter,Set<MultiProcParameter>>>();

    
    /**
     * @param designer
     * @param info
     */
    public LNSPartitioner(Designer designer, DesignerInfo info) {
        super(designer, info);
        this.costmodel = info.getCostModel();
        this.correlations = new ParameterCorrelations();
        assert(info.getCorrelationsFile() != null) : "The correlations file path was not set";
    }
    
    /**
     * Initialize the LNS partitioner. Creates all of the internal data structures that we need
     * for performing the various relaxations and local searches
     * @param hints
     * @throws Exception
     */
    protected void init(DesignerHints hints) throws Exception {
        final boolean trace = LOG.isTraceEnabled();
        final boolean debug = LOG.isDebugEnabled();
        
        this.init_called = true;
        this.agraph = this.generateAccessGraph();
        
        // HACK: Reload the correlations file so that we can get the proper catalog objects
        this.correlations.load(info.getCorrelationsFile(), info.catalog_db);
        
        // Gather all the information we need about each table
        for (Table catalog_tbl : CatalogKey.getFromKeys(info.catalog_db, AbstractPartitioner.generateTableOrder(info, this.agraph, hints), Table.class)) {
            // Potential Partitioning Attributes
            List<String> columns = AbstractPartitioner.generateColumnOrder(info, agraph, catalog_tbl, hints, false, true);
            assert(!columns.isEmpty()) : "No potential partitioning columns selected for " + catalog_tbl;
            this.orig_table_attributes.put(catalog_tbl, (ListOrderedSet<Column>)CollectionUtil.addAll(
                                                            new ListOrderedSet<Column>(),
                                                            CatalogKey.getFromKeys(info.catalog_db, columns, Column.class)));
            
            // Table Size (when the table is and is not replicated)
            TableStatistics ts = info.stats.getTableStatistics(catalog_tbl);
            this.table_nonreplicated_size.put(catalog_tbl, Math.round(ts.tuple_size_total / (double)this.num_partitions));
            this.table_replicated_size.put(catalog_tbl, ts.tuple_size_total);
        } // FOR
        
        // We also need to know some things about the Procedures and their ProcParameters
        for (Procedure catalog_proc : info.catalog_db.getProcedures()) {
            if (catalog_proc.getSystemproc() || catalog_proc.getParameters().size() == 0) continue;
            
            Set<Column> columns = CatalogUtil.getReferencedColumns(catalog_proc);
            if (columns.isEmpty()) {
                if (debug) LOG.warn("No columns for " + catalog_proc + ". Ignoring...");
                continue;
            }
            this.proc_columns.put(catalog_proc, columns);
            
            // Aha! Use the Procedure-specific AccessGraph to build our Histogram! Where's your god now??
            AccessGraph proc_agraph = this.designer.getAccessGraph(catalog_proc);
            assert(proc_agraph != null);
//            if (catalog_proc.getName().equals("GetAccessData")) {
//                GraphVisualizationPanel.createFrame(proc_agraph).setVisible(true);
//            }
            this.proc_column_histogram.put(catalog_proc, AbstractPartitioner.generateProcedureColumnAccessHistogram(info, hints, proc_agraph, catalog_proc));
            
            // Gather the list ProcParameters we should even consider for partitioning each procedure
            // TODO: For now we'll just put all the parameters in there plus the null one
            HashMap<ProcParameter, Set<MultiProcParameter>> multiparams = new HashMap<ProcParameter, Set<MultiProcParameter>>();
            if (hints.enable_multi_partitioning) {
                multiparams.putAll(AbstractPartitioner.generateMultiProcParameters(info, hints, catalog_proc));
                if (trace) LOG.trace(catalog_proc + " MultiProcParameters:\n" + multiparams);
            }
            this.proc_multipoc_map.put(catalog_proc, multiparams);
            
            ListOrderedSet<ProcParameter> params = new ListOrderedSet<ProcParameter>();
            CollectionUtil.addAll(params, CatalogUtil.getSortedCatalogItems(catalog_proc.getParameters(), "index"));
            params.add(NullProcParameter.getNullProcParameter(catalog_proc));
            this.orig_proc_attributes.put(catalog_proc, params);
            
            // Add multi-column partitioning parameters for each table
            if (hints.enable_multi_partitioning) {
                Map<Table, Set<MultiColumn>> multicolumns = AbstractPartitioner.generateMultiColumns(info, hints, catalog_proc);
                for (Entry<Table, Set<MultiColumn>> e : multicolumns.entrySet()) {
                    if (trace) LOG.trace(e.getKey().getName() + " MultiColumns:\n" + multicolumns);
                    this.orig_table_attributes.get(e.getKey()).addAll(e.getValue());
                } // FOR    
            }
        } // FOR
        
        // Go back through the table and create the sets of Procedures that touch Columns
        for (Entry<Table, ListOrderedSet<Column>> e : this.orig_table_attributes.entrySet()) {
            Table catalog_tbl = e.getKey();
            
            // We need to know which Procedures reference each table so that we can selectively choose
            // a new ProcParameter for each swap. Make sure we only include those Procedures that
            // we can make a decision about partitioning
            this.table_procedures.put(catalog_tbl, CatalogUtil.getReferencingProcedures(catalog_tbl));
            this.table_procedures.get(catalog_tbl).retainAll(this.orig_proc_attributes.keySet());
            
            // We actually be a bit more fine-grained in our costmodel cache invalidation if we know 
            // which columns are actually referenced in each of the procedures
            for (Column catalog_col : this.orig_table_attributes.get(catalog_tbl)) {
                Set<Procedure> procedures = CatalogUtil.getReferencingProcedures(catalog_col);
                procedures.retainAll(this.orig_proc_attributes.keySet());
                this.column_procedures.put(catalog_col, procedures);
            } // FOR
            
            // And also pre-compute the intersection of the procedure sets for each Column pair 
            for (Column catalog_col0 : this.orig_table_attributes.get(catalog_tbl)) {
                Map<Column, Set<Procedure>> intersections = new HashMap<Column, Set<Procedure>>();
                Set<Procedure> procs0 = this.column_procedures.get(catalog_col0);
                
                for (Column catalog_col1 : this.orig_table_attributes.get(catalog_tbl)) {
                    if (catalog_col0.equals(catalog_col1)) continue;
                    Set<Procedure> procs1 = this.column_procedures.get(catalog_col1);
                    intersections.put(catalog_col1, new HashSet<Procedure>(procs0));
                    intersections.get(catalog_col1).retainAll(procs1);
                } // FOR
                this.columnswap_procedures.put(catalog_col0, intersections);
            } // FOR
        } // FOR
        
//        LOG.info("\n" + this.debug());
    }

    /* (non-Javadoc)
     * @see edu.brown.designer.partitioners.AbstractPartitioner#generate(edu.brown.designer.DesignerHints)
     */
    @Override
    public PartitionPlan generate(DesignerHints hints) throws Exception {
        LOG.info(String.format("Starting Large-Neighborhood Search [partitions=%d, intervals=%d, total_bytes=%d]",
                               CatalogUtil.getNumberOfPartitions(info.catalog_db),
                               info.getArgs().num_intervals,
                               info.getMemoryEstimator().estimateTotalSize(info.catalog_db)
        ));
        
        // Initialize a bunch of stuff we need
        this.costmodel.applyDesignerHints(hints);
        this.init(hints);
        
        // Reload the checkpoint file so that we can continue this dirty mess!
        if (this.checkpoint != null && this.checkpoint.exists()) {
            this.load(this.checkpoint.getAbsolutePath(), info.catalog_db);
            LOG.info("Loaded checkpoint from '" + this.checkpoint.getName() + "'");
        }
        
        if (this.start_time == null && this.last_checkpoint == null) {
            this.start_time = hints.getStartTime();
        } else {
            LOG.info("Setting checkpoint offset times");
            hints.offsetCheckpointTime(this.start_time, this.last_checkpoint);
        }
        
        // First we need to hit up the MostPopularPartitioner in order to get an initial solution
        if (this.initial_solution == null) this.calculateInitialSolution(hints);
        assert(this.initial_solution != null);
        
        if (this.best_solution == null) {
            this.best_solution = new PartitionPlan(this.initial_solution);
            this.best_memory = this.initial_memory;
            this.best_cost = this.initial_cost;
        }
        assert(this.best_solution != null);
        
        // Relax and begin the search!
        this.costmodel.clear(true);
        if (this.restart_ctr == null) this.restart_ctr = 0;
        
        Map<Table, Set<Column>> table_attributes = new ListOrderedMap<Table, Set<Column>>();
        Map<Procedure, Set<ProcParameter>> proc_attributes = new ListOrderedMap<Procedure, Set<ProcParameter>>();
        
        while (true) {
            // IMPORTANT: Make sure that we are always start comparing swaps using the solution
            // at the beginning of a restart (or the start of the search). We do *not* want to 
            // compare swaps using the global best cost
            if (!this.relaxCurrentSolution(hints, this.restart_ctr++, table_attributes, proc_attributes)) {
                LOG.debug("Halting LNS!");
                break;
            }
            
            // Local Search!
            this.localSearch(hints, table_attributes, proc_attributes);
            
            // Sanity Check!
            this.best_solution.apply(info.catalog_db);
            this.costmodel.clear(true);
            double cost2 = this.costmodel.estimateCost(info.catalog_db, info.workload);
            assert(MathUtil.equals(this.best_cost, cost2, 2, 0.2)) : cost2 + " == " + this.best_cost + "\n" + PartitionPlan.createFromCatalog(info.catalog_db) + "\n" + this.costmodel.getLastDebugMessages();

            // Time Limit
            this.last_checkpoint = System.currentTimeMillis();
            if (this.last_checkpoint > hints.getGlobalStopTime()) {
                LOG.info("Time limit reached: " + hints.limit_total_time + " seconds");
                break;
            }
            
            // Save checkpoint
            if (this.checkpoint != null) {
                this.save(this.checkpoint.getAbsolutePath());
                LOG.info("Saved checkpoint to '" + this.checkpoint.getName() + "'");
            }
        } // WHILE
        LOG.info("Final Solution Cost: " + String.format("%.03f", this.best_cost));
        LOG.info("Final Solution Memory: " + String.format("%.03f", this.best_memory));
        assert(this.best_cost <= this.initial_cost);
        this.setProcedureSinglePartitionFlags(this.best_solution, hints);
        return (this.best_solution);
    }
    
    /**
     * 
     * @param hints
     * @throws Exception
     */
    protected void calculateInitialSolution(final DesignerHints hints) throws Exception {
        final boolean trace = LOG.isTraceEnabled();
        final boolean debug = LOG.isDebugEnabled();
        
        this.initial_solution = new MostPopularPartitioner(this.designer, this.info).generate(hints);
        this.initial_solution.apply(this.info.catalog_db);
        if (hints.max_memory_per_partition > 0) {
            this.initial_memory = this.info.getMemoryEstimator().estimate(this.info.catalog_db, this.num_partitions) /
                                  (double)hints.max_memory_per_partition;
            assert(this.initial_memory <= 1.0) : "Not enough memory: " + this.initial_memory; // Never should happen!
        } else {
            hints.max_memory_per_partition = 0;
        }
        this.initial_cost = this.costmodel.estimateCost(this.info.catalog_db, this.info.workload);
        
        // We need to examine whether the solution utilized all of the partitions. If it didn't, then
        // we need to increase the entropy weight in the costmodel. This will help steer us towards 
        // a solution that fully utilizes partitions
        Set<Integer> untouched_partitions = this.costmodel.getUntouchedPartitions(this.num_partitions);
        if (!untouched_partitions.isEmpty()) {
            double entropy_weight = this.costmodel.getEntropyWeight();
            entropy_weight *= this.num_partitions / (double)untouched_partitions.size();
            
            // Oh god this took forever to track down! If we want to re-adjust the weights, we have to make 
            // sure that we do it to the hints so that it is picked up by everyone!
            hints.weight_costmodel_entropy = entropy_weight;
            
            if (trace) LOG.trace("Initial Solution has " + untouched_partitions.size() + " unused partitions. New Entropy Weight: " + entropy_weight);
            this.costmodel.applyDesignerHints(hints);
            this.initial_cost = this.costmodel.estimateCost(this.info.catalog_db, this.info.workload);
        }
        
        if (debug) {
            LOG.debug("Initial Solution Cost: " + String.format("%.03f", this.initial_cost));
            LOG.debug("Initial Solution Memory: " + String.format("%.03f", this.initial_memory));
            LOG.debug("Initial Solution:\n" + this.initial_solution);
        }
        if (hints.shouldLogSolutionCosts()) hints.logSolutionCost(this.initial_cost);
    }
    
    /**
     * 
     * @param hints
     * @param restart_ctr
     * @throws Exception
     */
    protected boolean relaxCurrentSolution(final DesignerHints hints, int restart_ctr, Map<Table, Set<Column>> table_attributes, Map<Procedure, Set<ProcParameter>> proc_attributes) throws Exception {
        assert(this.init_called);
        RandomDistribution.DiscreteRNG rand = new RandomDistribution.Flat(this.rng, 0, this.orig_table_attributes.size());
        int num_tables = this.orig_table_attributes.size();
        
        // If the last search was exhaustive and its relaxation size was the same as the total number of tables,
        // then we know that we're done! This won't happen too often...
        if (this.last_relax_size == num_tables && this.last_exhausted_search) {
            LOG.info("Exhaustively search solution space! Nothing more to do!");
            return (false);
        }
        
        // Apply the best solution to the catalog
        this.best_solution.apply(info.catalog_db);
        
        // TODO: Calculate relaxation weights
        //Map<Table, Double> relax_weights = new HashMap<Table, Double>();
        
        // Figure out what how far along we are in the search and use that to determine how many to relax
        int relax_min = (int)Math.round(RELAXATION_FACTOR_MIN * num_tables);
        int relax_max = (int)Math.max(RELAXATION_MIN_SIZE, (int)Math.round(RELAXATION_FACTOR_MIN * num_tables));
        
        // We should probably try to do something smart here, but for now we can just be random
//        int relax_size = (int)Math.round(RELAXATION_FACTOR_MIN * num_tables) + (restart_ctr / 2);
        double elapsed_ratio = hints.getElapsedGlobalPercent();
        int relax_size = (int)Math.max(this.last_relax_size, (int)Math.round(((relax_max - relax_min) * elapsed_ratio) + relax_min));
        if (relax_size > num_tables) relax_size = num_tables; 
        // if (this.last_exhausted_search && relax_size < relax_max) relax_size = this.last_relax_size + 1;
        assert(relax_size >= relax_min)  : "Invalid Relax Size: " + relax_size;
        assert(relax_size <= relax_max)  : "Invalid Relax Size: " + relax_size;
        assert(relax_size > 0)           : "Invalid Relax Size: " + relax_size;
        assert(relax_size <= num_tables) : "Invalid Relax Size: " + relax_size;
        
        if (LOG.isInfoEnabled()) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("LNS RESTART #%03d  [relax_size=%d]\n", restart_ctr, relax_size))
              .append(String.format(" - last_relax_size = %d\n", this.last_relax_size))
              .append(String.format(" - last_exhausted  = %s\n", this.last_exhausted_search))
              .append(String.format(" - elapsed_ratio   = %.02f\n", elapsed_ratio));
            LOG.info("\n" + StringUtil.box(sb.toString(), "*", 80));
        }

        // Select which tables we want to relax on this restart
        List<Table> nonrelaxed_tables = new ArrayList<Table>(this.orig_table_attributes.asList());
        List<Table> relaxed_tables = new ArrayList<Table>();
        SortedSet<Integer> rand_idxs = new TreeSet<Integer>(rand.getRandomIntSet(relax_size));
        LOG.debug("Relaxed Table Identifiers: " + rand_idxs);
        for (int idx : rand_idxs) {
            assert(idx < this.orig_table_attributes.size()) : "Random Index is too large: " + idx + " " + this.orig_table_attributes.keySet();
            Table catalog_tbl = this.orig_table_attributes.get(idx);
            relaxed_tables.add(catalog_tbl);
            nonrelaxed_tables.remove(catalog_tbl);
            this.costmodel.invalidateCache(catalog_tbl);
        } // FOR
        assert(relaxed_tables.size() == relax_size) : relax_size + " => " + relaxed_tables;
        LOG.info("Relaxed Tables: " + relaxed_tables);
        
        // Now estimate the size of a partition for the non-relaxed tables
        double nonrelaxed_memory = this.info.getMemoryEstimator().estimate(info.catalog_db, this.num_partitions, nonrelaxed_tables) /
                                   (double)hints.max_memory_per_partition;
        assert(nonrelaxed_memory >= 0.0) : "Invalid memory: " + nonrelaxed_memory;
        assert(nonrelaxed_memory < 1.0) : "Invalid memory: " + nonrelaxed_memory;
        
        // THOUGHTS:
        // (0) We think we actually need to relax very first time too...
        // (1) Make it less likely that we are going to relax a small/read-only table
        //
        
        // Now for each of the relaxed tables, figure out what columns we want to consider for swaps
        table_attributes.clear();
        proc_attributes.clear();
        Collections.shuffle(relaxed_tables);
        for (Table catalog_tbl : relaxed_tables) {
            // For now just throw in all of the columns
            // This is where we could look at the fixed tables and try to infer what columns we know 
            // we won't need. Unfortunately I think that is too difficult because of the way the iterative
            // costmodel works. The cost of choosing a partitioning column may not be evident until other
            // tables have their columns selected. Furthermore, it may be the case that a txn is going to need to
            // be multi-partitioned in order to balance the load properly, and thus we can't just throw out any columns
            // that cause txns to become multi-partitioned...
            List<Column> table_cols = new ArrayList<Column>(this.orig_table_attributes.get(catalog_tbl));
            Collections.shuffle(table_cols, this.rng);
            table_attributes.put(catalog_tbl, new HashSet<Column>(table_cols));
            
            for (Procedure catalog_proc : this.table_procedures.get(catalog_tbl)) {
                proc_attributes.put(catalog_proc, null); // Placeholder
            } // FOR
        } // FOR
        
        // Now add in all of the attributes for the procedures we will want to investigate
        for (Procedure catalog_proc : proc_attributes.keySet()) {
            HashSet<ProcParameter> params = new HashSet<ProcParameter>();
            for (ProcParameter catalog_param : catalog_proc.getParameters()) {
                if (catalog_param.getIsarray()) continue;
                params.add(catalog_param);
            } // FOR
            proc_attributes.put(catalog_proc, params);
        }
        
        this.last_exhausted_search = false;
        this.last_relax_size = relax_size;
        return (true);
    }
    
    /**
     * Local Search
     * @param hints
     * @param table_attributes
     * @param proc_attributes
     * @throws Exception
     */
    protected void localSearch(final DesignerHints hints, Map<Table, Set<Column>> table_attributes, Map<Procedure, Set<ProcParameter>> proc_attributes) throws Exception {
        
        // -------------------------------
        // Convert to catalog keys and apply relaxation
        // -------------------------------
        Map<String, List<String>> key_attributes = new ListOrderedMap<String, List<String>>();
        for (Entry<Table, Set<Column>> e : table_attributes.entrySet()) {
            List<String> column_keys = new ArrayList<String>(CatalogKey.createKeys(e.getValue()));
            key_attributes.put(CatalogKey.createKey(e.getKey()), column_keys);
            this.costmodel.invalidateCache(e.getKey());
        } // FOR
        for (Entry<Procedure, Set<ProcParameter>> e : proc_attributes.entrySet()) {
            List<String> param_keys = new ArrayList<String>(CatalogKey.createKeys(e.getValue()));
            key_attributes.put(CatalogKey.createKey(e.getKey()), param_keys);
            e.getKey().setPartitionparameter(NullProcParameter.PARAM_IDX);
            this.costmodel.invalidateCache(e.getKey());
        } // FOR
        
        // Sanity Check: Make sure the non-relaxed tables come back with the same partitioning attribute
        Map<CatalogType, CatalogType> orig_solution = new HashMap<CatalogType, CatalogType>();
        for (Table catalog_tbl : info.catalog_db.getTables()) {
            if (!table_attributes.containsKey(catalog_tbl)) orig_solution.put(catalog_tbl, catalog_tbl.getPartitioncolumn());
        }
        for (Procedure catalog_proc : info.catalog_db.getProcedures()) {
            if (!proc_attributes.containsKey(catalog_proc)) {
                ProcParameter catalog_param = catalog_proc.getParameters().get(catalog_proc.getPartitionparameter());
                orig_solution.put(catalog_proc, catalog_param);
            }
        }

        // -------------------------------
        // Calculate the number of backtracks and the local search time 
        // we want to allow in this round. 
        // -------------------------------
        
        Integer backtracks = hints.limit_back_tracks;
        Integer local_time = hints.limit_local_time;
        if (this.last_exhausted_search) {
            
        }
        hints.limit_back_tracks = backtracks;
        hints.limit_local_time = local_time;
        
        
        
        // -------------------------------
        // GO GO LOCAL SEARCH!!
        // -------------------------------
        Pair<PartitionPlan, BranchAndBoundPartitioner.StateVertex> pair = this.executeLocalSearch(hints, table_attributes, key_attributes);
        assert(pair != null);
        PartitionPlan result = pair.getFirst();
        BranchAndBoundPartitioner.StateVertex state = pair.getSecond();

        // -------------------------------
        // Validation
        // -------------------------------
        for (Table catalog_tbl : info.catalog_db.getTables()) {
            if (orig_solution.containsKey(catalog_tbl)) {
                assert(catalog_tbl.getPartitioncolumn().equals(orig_solution.get(catalog_tbl))) :
                    catalog_tbl + " got changed: " + orig_solution.get(catalog_tbl) + " => " + catalog_tbl.getPartitioncolumn();
            }
        } // FOR
        for (Procedure catalog_proc : info.catalog_db.getProcedures()) {
            if (orig_solution.containsKey(catalog_proc)) {
                ProcParameter catalog_param = catalog_proc.getParameters().get(catalog_proc.getPartitionparameter());
                if (catalog_param == null) {
                    assert(orig_solution.get(catalog_proc) == null) :
                        catalog_proc + " got changed: " + orig_solution.get(catalog_proc) + " => " + catalog_param + "\n" + result;
                } else {
                    assert(catalog_param.equals(orig_solution.get(catalog_proc))) :
                        catalog_proc + " got changed: " + orig_solution.get(catalog_proc) + " => " + catalog_param + "\n" + result;
                }
            }
        } // FOR
        
        // -------------------------------
        // Comparison with current best solution
        // -------------------------------
        if (state.getCost() < this.best_cost) {
            LOG.info("New Best Solution Found from Local Search!");
            this.best_solution = result;
            this.best_cost = state.getCost();
            this.best_memory = state.getMemory() / (double)hints.max_memory_per_partition;
            LOG.info("Best Solution Cost: " + String.format("%.03f", this.best_cost));
            LOG.info("Best Solution Memory: " + String.format("%.03f", this.best_memory));
            LOG.info("Best Solution:\n" + this.best_solution);
        }
        this.best_solution.apply(info.catalog_db);
        return;
    }

    /**
     * 
     * @param hints
     * @param table_attributes
     * @param key_attributes
     * @return
     * @throws Exception
     */
    protected Pair<PartitionPlan, BranchAndBoundPartitioner.StateVertex> executeLocalSearch(final DesignerHints hints,
                                                                                            final Map<Table, Set<Column>> table_attributes,
                                                                                            final Map<String, List<String>> key_attributes) throws Exception {
        BranchAndBoundPartitioner local_search = new BranchAndBoundPartitioner(this.designer, this.info);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Best Solution Cost:   " + this.best_cost);
            LOG.debug("Best Solution Memory: " + this.best_memory);
        }
        
        local_search.setUpperBounds(hints, this.best_solution, this.best_cost, (long)(this.best_memory * hints.max_memory_per_partition));
        local_search.setTraversalAttributes(key_attributes, table_attributes.size());
        PartitionPlan result = local_search.generate(hints);
        this.last_exhausted_search = (local_search.wasHalted() != null && local_search.wasHalted() == false); 

        return (Pair.of(result, local_search.getBestVertex()));
    }
    
    /**
     * 
     * @param hints
     * @return
     * @throws Exception
     */
    protected Pair<Double, Double> recalculateCost(final DesignerHints hints) throws Exception {
        long memory = info.getMemoryEstimator().estimate(info.catalog_db, this.num_partitions, this.orig_table_attributes.keySet());
        double memory_ratio = memory / (double)hints.max_memory_per_partition;
        double cost = this.costmodel.estimateCost(info.catalog_db, info.workload);
        return (Pair.of(cost, memory_ratio));
    }
    
    /**
     * 
     * @param hints
     * @param procs
     * @throws Exception
     */
    protected void recalculateProcParameters(final DesignerHints hints, Iterable<Procedure> procs) throws Exception {
        Map<Procedure, Integer> new_procparams = new HashMap<Procedure, Integer>();
        for (Procedure catalog_proc : procs) {
            if (catalog_proc.getSystemproc() || catalog_proc.getParameters().size() == 0) continue;
            // If we get back a null ProcParameter, then just the Procedure alone
            ProcParameter catalog_proc_param = this.findBestProcParameter(hints, catalog_proc);
            if (catalog_proc_param != null) new_procparams.put(catalog_proc, catalog_proc_param.getIndex());
        } // FOR
        this.applyProcParameterSwap(hints, new_procparams);
    }
    
    /**
     * 
     * @param hints
     * @param catalog_proc
     * @return
     * @throws Exception
     */
    protected ProcParameter findBestProcParameter(final DesignerHints hints, final Procedure catalog_proc) throws Exception {
        assert(!catalog_proc.getSystemproc());
        assert(catalog_proc.getParameters().size() > 0);
        boolean debug = LOG.isDebugEnabled();
        
        // Find all the ProcParameter correlations that map to the target column in the Procedure
//        ParameterCorrelations correlations = info.getCorrelations();
//        assert(correlations != null);

        ProcParameter default_param = catalog_proc.getParameters().get(0);
        Histogram col_access_histogram = this.proc_column_histogram.get(catalog_proc);
        if (col_access_histogram == null) {
            if (debug) LOG.warn("No column access histogram for " + catalog_proc + ". Setting to default");
            return (default_param);
        }
        if (debug) LOG.debug(catalog_proc + " Column Histogram:\n" + col_access_histogram);
        
        // Loop through each Table and check whether its partitioning column is referenced in this procedure
        Map<ProcParameter, List<Double>> param_weights = new HashMap<ProcParameter, List<Double>>();
        for (Table catalog_tbl : info.catalog_db.getTables()) {
            if (catalog_tbl.getIsreplicated()) continue;
            Column catalog_col = catalog_tbl.getPartitioncolumn();
            if (!col_access_histogram.contains(catalog_col)) continue;
            long col_access_cnt = col_access_histogram.get(catalog_col);
            
            if (debug) LOG.debug(CatalogUtil.getDisplayName(catalog_col));
            // Now loop through the ProcParameters and figure out which ones are correlated to the Column
            for (ProcParameter catalog_proc_param : catalog_proc.getParameters()) {
                // Skip if this is an array
                if (catalog_proc_param.getIsarray()) continue;
                
                if (!param_weights.containsKey(catalog_proc_param)) {
                    param_weights.put(catalog_proc_param, new ArrayList<Double>());
                }
                List<Double> weights_list = param_weights.get(catalog_proc_param);
                for (Correlation c : correlations.get(catalog_proc_param, catalog_col)) {
                    weights_list.add(c.getCoefficient() * col_access_cnt);
                } // FOR
                if (debug) LOG.debug("  " + catalog_proc_param + ": " + weights_list);
            } // FOR
            if (debug) LOG.debug("");
        } // FOR (Table)
        
        final Map<ProcParameter, Double> final_param_weights = new HashMap<ProcParameter, Double>();
        for (Entry<ProcParameter, List<Double>> e : param_weights.entrySet()) {
            // The weights for each ProcParameter will be the geometric mean of the correlation coefficients
            if (!e.getValue().isEmpty()) {
                double weights[] = new double[e.getValue().size()];
                for (int i = 0; i < weights.length; i++) 
                    weights[i] = e.getValue().get(i);
                final_param_weights.put(e.getKey(), MathUtil.geometricMean(weights, MathUtil.GEOMETRIC_MEAN_ZERO));
            }
        } // FOR
        if (final_param_weights.isEmpty()) {
            if (debug) LOG.warn("Failed to find any ProcParameters for " + catalog_proc.getName() + " that map to partition columns");
            return (default_param);
        }
        Map<ProcParameter, Double> sorted = CollectionUtil.sortByValues(final_param_weights, true);
        assert(sorted != null);
        ProcParameter best_param = CollectionUtil.getFirst(sorted.keySet());
        if (debug) LOG.debug("Best Param: " + best_param  + " " + sorted);
        return (best_param);
    }
    
    
    protected void applyColumnSwap(DesignerHints hints, Table catalog_tbl, Column new_column) throws Exception {
        assert(catalog_tbl != null);
        assert(new_column != null);
        catalog_tbl.setIsreplicated(new_column instanceof ReplicatedColumn);
        catalog_tbl.setPartitioncolumn(new_column);
        if (hints.enable_costmodel_caching) {
            this.costmodel.invalidateCache(catalog_tbl);
        } else {
            this.costmodel.clear();
        }
    }
    
    protected void applyProcParameterSwap(DesignerHints hints, Map<Procedure, Integer> param_map) {
        for (Entry<Procedure, Integer> e : param_map.entrySet()) {
            int orig_param = e.getKey().getPartitionparameter();
            e.getKey().setPartitionparameter(e.getValue());
            if (orig_param != e.getValue()) {
                if (hints.enable_costmodel_caching) {
                    this.costmodel.invalidateCache(e.getKey());
                } else {
                    this.costmodel.clear();
                }
            } 
        } // FOR
    }
    
    public String dumpCatalogState(Database catalog_db) {
        return (PartitionPlan.createFromCatalog(catalog_db).toString());
    }
    
    public String debug() {
        StringBuilder sb = new StringBuilder();
        
        sb.append(StringUtil.repeat("=", 100)).append("\n")
          .append("TABLE INFORMATION\n")
          .append(StringUtil.repeat("=", 100)).append("\n");
        for (int i = 0, cnt = this.orig_table_attributes.size(); i < cnt; i++) {
            Table catalog_tbl = this.orig_table_attributes.get(i);
            ListOrderedSet<Column> cols = this.orig_table_attributes.get(catalog_tbl);
            
            sb.append(String.format("[%02d] %s\n", i, catalog_tbl.getName()));
            sb.append("   Column Candidates [").append(cols.size()).append("]\n");
            for (int ii = 0; ii < cols.size(); ii++) {
                sb.append("      ")
                  .append(String.format("[%02d] %s\n", ii, cols.get(ii).getName()));
            } // FOR (cols)
        } // FOR (tables)
        sb.append("\n");
        
        sb.append(StringUtil.repeat("=", 100)).append("\n")
          .append("PROCEDURE INFORMATION\n")
          .append(StringUtil.repeat("=", 100)).append("\n");
        for (int i = 0, cnt = this.orig_proc_attributes.size(); i < cnt; i++) {
            Procedure catalog_proc = this.orig_proc_attributes.get(i);
            ListOrderedSet<ProcParameter> params = this.orig_proc_attributes.get(catalog_proc);
              
            sb.append(String.format("[%02d] %s\n", i, catalog_proc.getName()));
            sb.append("   Parameter Candidates [").append(params.size()).append("]\n");
            for (int ii = 0; ii < params.size(); ii++) {
                sb.append("      ")
                  .append(String.format("[%02d] %s\n", ii, params.get(ii).getName()));
            } // FOR (params)
        } // FOR (procedres)
        sb.append("\n");
        
        return (sb.toString());
    }
    
    // ----------------------------------------------------------------------------
    // SERIALIZATION METHODS
    // ----------------------------------------------------------------------------

    @Override
    public void load(String input_path, Database catalog_db) throws IOException {
        JSONUtil.load(this, catalog_db, input_path);
    }
    
    @Override
    public void save(String output_path) throws IOException {
        JSONUtil.save(this, output_path);
    }
    
    @Override
    public String toJSONString() {
        return (JSONUtil.toJSONString(this));
    }
    
    @Override
    public void toJSON(JSONStringer stringer) throws JSONException {
        JSONUtil.fieldsToJSON(stringer, this, LNSPartitioner.class, LNSPartitioner.Members.values());
    }
    
    @Override
    public void fromJSON(JSONObject json_object, Database catalog_db) throws JSONException {
        JSONUtil.fieldsFromJSON(json_object, catalog_db, this, LNSPartitioner.class, true, LNSPartitioner.Members.values());        
    }

}
