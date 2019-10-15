package com.kalessil.phpStorm.phpInspectionsEA.inspectors.regularExpressions.modifiersStrategy;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.kalessil.phpStorm.phpInspectionsEA.utils.ReportingUtil;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/*
 * This file is part of the Php Inspections (EA Extended) package.
 *
 * (c) Vladimir Reznichenko <kalessil@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

final public class MissingUnicodeModifierStrategy {
    private static final String messageCharacters = "/u modifier is missing (unicode characters found).";
    private static final String messageCodepoints = "/u modifier is missing (unicode codepoints found).";

    final static private Pattern unicodeCharactersPattern;
    final static private Pattern unicodeCodepointsPattern;
    static {
        // Original regex: .*[^\u0000-\u007F]+.*
        unicodeCharactersPattern = Pattern.compile(".*[^\\u0000-\\u007F]+.*");
        // Original regex: .*\\[pPX].*
        unicodeCodepointsPattern = Pattern.compile(".*\\\\[pPX].*");
    }

    static public boolean apply(
            @NotNull String functionName,
            @Nullable String modifiers,
            @Nullable String pattern,
            @NotNull PsiElement target,
            @NotNull  ProblemsHolder holder
    ) {
        boolean result = false;
        if ((modifiers == null || modifiers.indexOf('u') == -1) && pattern != null && ! pattern.isEmpty() && ! functionName.equals("preg_quote")) {
            if (result = unicodeCharactersPattern.matcher(pattern).matches()) {
                holder.registerProblem(target, ReportingUtil.wrapReportedMessage(messageCharacters), ProblemHighlightType.GENERIC_ERROR);
            } else {
                final String normalized = StringUtils.replace(pattern, "\\\\", "");
                if (result = unicodeCodepointsPattern.matcher(normalized).matches()) {
                    holder.registerProblem(target, ReportingUtil.wrapReportedMessage(messageCodepoints), ProblemHighlightType.GENERIC_ERROR);
                }
            }
        }
        return result;
    }
}
