package com.example.epub.core.service.catalog;

import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Pure, bounded ranking. Weights are starting hypotheses, not learned parameters. */
public final class RecommendationScorer {
    public static final int HISTORY_LIMIT = 100, CANDIDATE_LIMIT = 200;
    static final double AFFINITY_WEIGHT = .55, POPULARITY_WEIGHT = .25, FRESHNESS_WEIGHT = .20;
    public record Item(UUID id, Set<UUID> categories, Set<String> tags, Set<UUID> authors,
                       Instant createdAt, double popularity) { }
    public record Signal(Item item, double weight, Instant at) { }
    public record Pick(UUID id, String reasonCode, UUID categoryId) { }
    public record Profile(Map<UUID,Double> categories, Map<String,Double> tags, Map<UUID,Double> authors) {
        public boolean empty() { return categories.isEmpty() && tags.isEmpty() && authors.isEmpty(); }
        public UUID mainCategory() { return categories.entrySet().stream().sorted(Map.Entry.<UUID,Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).map(Map.Entry::getKey).findFirst().orElse(null); }
    }
    private record Scored(Item item, double affinity, double popularity, double freshness, double score) { }
    private RecommendationScorer() { }
    public static String tag(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
    public static double decay(Instant at, Instant now) {
        return Math.pow(.5, Math.max(0, Duration.between(at, now).toSeconds()) / (30.0 * 86400));
    }
    public static Profile profile(List<Signal> signals, Instant now) {
        Map<UUID,Double> categories=new HashMap<>(), authors=new HashMap<>(); Map<String,Double> tags=new HashMap<>();
        for(var s:signals) {
            double weight=s.weight()*decay(s.at(),now);
            contribute(categories,s.item().categories(),weight);contribute(tags,s.item().tags(),weight);contribute(authors,s.item().authors(),weight);
        }
        normalize(categories);normalize(tags);normalize(authors);
        return new Profile(Map.copyOf(categories),Map.copyOf(tags),Map.copyOf(authors));
    }
    private static <K> void contribute(Map<K,Double> scores,Set<K> features,double weight) {
        for(K feature:features)scores.merge(feature,weight/features.size(),Double::sum);
    }
    private static <K> void normalize(Map<K,Double> scores) {
        double max=scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if(max>0)scores.replaceAll((k,v)->v/max);else scores.clear();
    }
    private static <K> double match(Set<K> features,Map<K,Double> profile) {
        return features.stream().mapToDouble(k->profile.getOrDefault(k,0.0)).average().orElse(0);
    }
    public static List<Pick> rank(List<Item> items,Profile profile,Instant now,int limit) {
        double maxPopularity=items.stream().mapToDouble(i->Math.log1p(Math.max(0,i.popularity()))).max().orElse(0);
        List<Scored> scored=items.stream().map(i->{
            double affinity=.60*match(i.categories(),profile.categories())+.25*match(i.tags(),profile.tags())+.15*match(i.authors(),profile.authors());
            double popularity=maxPopularity==0?0:Math.log1p(Math.max(0,i.popularity()))/maxPopularity;
            double fresh=decay(i.createdAt(),now);
            return new Scored(i,affinity,popularity,fresh,AFFINITY_WEIGHT*affinity+POPULARITY_WEIGHT*popularity+FRESHNESS_WEIGHT*fresh);
        }).toList();
        Comparator<Scored> overall=Comparator.comparingDouble(Scored::score).reversed().thenComparing(s->s.item().id());
        var ranked=scored.stream().sorted(overall).toList();
        var popular=scored.stream().filter(s->s.popularity()>0).sorted(Comparator.comparingDouble(Scored::popularity).reversed().thenComparing(overall)).toList();
        var fresh=scored.stream().sorted(Comparator.comparingDouble(Scored::freshness).reversed().thenComparing(s->s.item().id())).toList();
        List<Pick> result=new ArrayList<>();Set<UUID> selected=new HashSet<>();Map<UUID,Integer> authorCounts=new HashMap<>();
        UUID main=profile.mainCategory();
        if(!profile.empty()) {
            add(ranked.stream().filter(s->s.affinity()>0).toList(),Math.max(1,limit-2),null,profile,result,selected,authorCounts,true);
            add(popular,Math.min(limit,result.size()+1),"POPULAR",profile,result,selected,authorCounts,true);
            if(main!=null)add(ranked.stream().filter(s->!s.item().categories().isEmpty()&&!s.item().categories().contains(main)).toList(),Math.min(limit,result.size()+1),"DISCOVERY",profile,result,selected,authorCounts,true);
        } else {
            add(popular,(limit+1)/2,"POPULAR",profile,result,selected,authorCounts,true);
            add(fresh,limit,"NEW",profile,result,selected,authorCounts,true);
        }
        add(ranked,limit,null,profile,result,selected,authorCounts,true);
        add(ranked,limit,null,profile,result,selected,authorCounts,false);
        return List.copyOf(result);
    }
    private static void add(List<Scored> source,int target,String forced,Profile p,List<Pick> result,Set<UUID> selected,Map<UUID,Integer> authors,boolean diverse) {
        for(var s:source) {
            if(result.size()>=target)return;
            var i=s.item();
            if(selected.contains(i.id()) || diverse&&i.authors().stream().anyMatch(a->authors.getOrDefault(a,0)>=2))continue;
            UUID category=i.categories().stream().filter(p.categories()::containsKey).sorted(Comparator.<UUID>comparingDouble(k->p.categories().get(k)).reversed().thenComparing(Function.identity())).findFirst().orElse(null);
            String reason=forced;
            if(reason==null)reason=category!=null?"SAME_CATEGORY":match(i.authors(),p.authors())>0?"SAME_AUTHOR":match(i.tags(),p.tags())>0?"SAME_TAG":s.popularity()>0?"POPULAR":"NEW";
            result.add(new Pick(i.id(),reason,reason.equals("SAME_CATEGORY")?category:null));selected.add(i.id());
            i.authors().forEach(a->authors.merge(a,1,Integer::sum));
        }
    }
}
