package com.ethlo.r7.util;

public class Levenshtein
{
    private Levenshtein()
    {
    }

    public static String findClosestMatch(final String target, final Iterable<String> candidates)
    {
        String closest = null;
        int minDistance = Integer.MAX_VALUE;

        for (final String candidate : candidates)
        {
            final int distance = calculateLevenshteinDistance(target.toLowerCase(), candidate.toLowerCase());
            // Only suggest if it's reasonably close (max 5 typos)
            if (distance < minDistance && distance <= 5)
            {
                minDistance = distance;
                closest = candidate;
            }
        }
        return closest;
    }

    private static int calculateLevenshteinDistance(final String s1, final String s2)
    {
        final int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= s1.length(); i++)
        {
            for (int j = 1; j <= s2.length(); j++)
            {
                final int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[s1.length()][s2.length()];
    }
}
