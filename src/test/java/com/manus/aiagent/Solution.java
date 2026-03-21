package com.manus.aiagent;

import java.util.*;
import java.util.PriorityQueue;

class Solution {
    public int[] topKFrequent(int[] nums, int k) {
        Map<Integer, Integer> map = new HashMap<>();
        for(int i : nums){
            map.merge(i, 1, Integer::sum);
        }
        int maxCnt = Collections.max(map.values());
        List<Integer>[] buckets = new List[maxCnt+1];
        Arrays.setAll(buckets, a -> new ArrayList<>());
        for(Map.Entry<Integer, Integer> e: map.entrySet()) {
            buckets[e.getValue()].add(e.getKey());
        }
//        Character
        int[] ans = new int[k];
        int j = 0;
        for(int i = maxCnt; i >= 0 && j < k; j --) {
            for(int x : buckets[i]) {
                ans[j++] = x;
            }
        }
        return ans;
    }

    public static void main(String[] args) {
        String a = "asdfaf";
        System.out.println(a);
//        Thread
        int b = 123;
        int mod = 3;
        System.out.println(b ^ 0);
    }
}
