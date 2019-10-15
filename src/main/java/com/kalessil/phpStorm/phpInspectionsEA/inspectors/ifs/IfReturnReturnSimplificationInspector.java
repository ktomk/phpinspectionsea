package com.kalessil.phpStorm.phpInspectionsEA.inspectors.ifs;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Couple;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.jetbrains.php.lang.inspections.PhpInspection;
import com.jetbrains.php.lang.lexer.PhpTokenTypes;
import com.jetbrains.php.lang.psi.PhpPsiElementFactory;
import com.jetbrains.php.lang.psi.elements.*;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.FeaturedPhpElementVisitor;
import com.kalessil.phpStorm.phpInspectionsEA.settings.StrictnessCategory;
import com.kalessil.phpStorm.phpInspectionsEA.utils.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/*
 * This file is part of the Php Inspections (EA Extended) package.
 *
 * (c) Vladimir Reznichenko <kalessil@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

public class IfReturnReturnSimplificationInspector extends PhpInspection {
    private static final String messagePattern = "The construct can be replaced with '%s'.";

    @NotNull
    @Override
    public String getShortName() {
        return "IfReturnReturnSimplificationInspection";
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "If-return-return could be simplified";
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new FeaturedPhpElementVisitor() {
            @Override
            public void visitPhpIf(@NotNull If statement) {
                if (this.shouldSkipAnalysis(statement, StrictnessCategory.STRICTNESS_CATEGORY_CONTROL_FLOW)) { return; }

                final PsiElement condition = ExpressionSemanticUtil.getExpressionTroughParenthesis(statement.getCondition());
                if (condition != null && this.isTargetCondition(condition) && statement.getElseIfBranches().length == 0) {
                    final Couple<Couple<PsiElement>> fragments = this.extract(statement);
                    final PsiElement firstValue                = fragments.second.first;
                    final PsiElement secondValue               = fragments.second.second;

                    /* if 2nd return found, check more pattern matches */
                    if (firstValue != null && secondValue != null) {
                        final boolean isDirect  = PhpLanguageUtil.isTrue(firstValue) && PhpLanguageUtil.isFalse(secondValue);
                        final boolean isReverse = !isDirect && PhpLanguageUtil.isTrue(secondValue) && PhpLanguageUtil.isFalse(firstValue);
                        if (isDirect || isReverse) {
                            /* false-positives: if-return if-return return - code style */
                            if (statement.getElseBranch() == null) {
                                final PsiElement before = statement.getPrevPsiSibling();
                                if (before instanceof If && !ExpressionSemanticUtil.hasAlternativeBranches((If) before)) {
                                    final GroupStatement prevBody = ExpressionSemanticUtil.getGroupStatement(before);
                                    if (prevBody != null && ExpressionSemanticUtil.getLastStatement(prevBody) instanceof PhpReturn) {
                                        return;
                                    }
                                }
                            }

                            /* generate replacement */
                            String replacement;
                            if (isReverse) {
                                if (condition instanceof UnaryExpression) {
                                    PsiElement extracted = ((UnaryExpression) condition).getValue();
                                    extracted            = ExpressionSemanticUtil.getExpressionTroughParenthesis(extracted);
                                    replacement          = String.format("return %s", extracted == null ? "" : extracted.getText());
                                } else {
                                    replacement = String.format("return !(%s)", condition.getText());
                                }
                            } else {
                                replacement = String.format("return %s", condition.getText());
                            }
                            /* preserve the assignment anyway */
                            final PsiElement possiblyAssignment = firstValue.getParent();
                            if (OpenapiTypesUtil.isAssignment(possiblyAssignment)) {
                                final PsiElement container = ((AssignmentExpression) possiblyAssignment).getVariable();
                                if (container != null) {
                                    replacement = replacement.replace("return ", String.format("return %s = ", container.getText()));
                                }
                            }
                            holder.registerProblem(
                                    statement.getFirstChild(),
                                    String.format(ReportingUtil.wrapReportedMessage(messagePattern), replacement),
                                    new SimplifyFix(holder.getProject(), fragments.first.first, fragments.first.second, replacement)
                            );
                        }
                    }
                }
            }

            /* first pair: what to drop, second positive and negative branching values */
            private Couple<Couple<PsiElement>> extract(@NotNull If statement) {
                Couple<Couple<PsiElement>> result = new Couple<>(new Couple<>(null, null), new Couple<>(null, null));

                final GroupStatement ifBody = ExpressionSemanticUtil.getGroupStatement(statement);
                if (ifBody != null && ExpressionSemanticUtil.countExpressionsInGroup(ifBody) == 1) {
                    final PsiElement ifLast = this.extractCandidate(ExpressionSemanticUtil.getLastStatement(ifBody));
                    if (ifLast != null) {
                        /* extract all related constructs */
                        final PsiElement ifNext     = this.extractCandidate(statement.getNextPsiSibling());
                        final PsiElement ifPrevious = this.extractCandidate(statement.getPrevPsiSibling());

                        if (statement.getElseBranch() != null) {
                            PsiElement elseLast         = null;
                            final GroupStatement elseBody = ExpressionSemanticUtil.getGroupStatement(statement.getElseBranch());
                            if (elseBody != null && ExpressionSemanticUtil.countExpressionsInGroup(elseBody) == 1) {
                                elseLast = this.extractCandidate(ExpressionSemanticUtil.getLastStatement(elseBody));
                            }

                            /* if - return - else - return */
                            if (ifLast instanceof PhpReturn && elseLast instanceof PhpReturn) {
                                result = new Couple<>(
                                        new Couple<>(statement, statement),
                                        new Couple<>(((PhpReturn) ifLast).getArgument(), ((PhpReturn) elseLast).getArgument())
                                );
                            }
                            /* if - assign - else - assign - return */
                            else if (ifLast instanceof AssignmentExpression && elseLast instanceof AssignmentExpression && ifNext instanceof PhpReturn) {
                                final AssignmentExpression ifAssignment   = (AssignmentExpression) ifLast;
                                final AssignmentExpression elseAssignment = (AssignmentExpression) elseLast;
                                final PsiElement ifContainer              = ifAssignment.getVariable();
                                final PsiElement elseContainer            = elseAssignment.getVariable();
                                final PsiElement returnedValue            = ((PhpReturn) ifNext).getArgument();
                                if (ifContainer instanceof Variable && elseContainer instanceof Variable && returnedValue instanceof Variable) {
                                    final boolean isTarget = OpenapiEquivalenceUtil.areEqual(ifContainer, elseContainer) &&
                                                             OpenapiEquivalenceUtil.areEqual(elseContainer, returnedValue);
                                    if (isTarget) {
                                        result = new Couple<>(
                                                new Couple<>(statement, ifNext),
                                                new Couple<>(ifAssignment.getValue(), elseAssignment.getValue())
                                        );
                                    }
                                }
                            }
                        } else {
                            /* assign - if - assign - return */
                            if (ifPrevious instanceof AssignmentExpression && ifLast instanceof AssignmentExpression && ifNext instanceof PhpReturn) {
                                final AssignmentExpression previousAssignment = (AssignmentExpression) ifPrevious;
                                final AssignmentExpression ifAssignment       = (AssignmentExpression) ifLast;
                                final PsiElement previousContainer            = previousAssignment.getVariable();
                                final PsiElement ifContainer                  = ifAssignment.getVariable();
                                final PsiElement returnedValue                = ((PhpReturn) ifNext).getArgument();
                                if (previousContainer instanceof Variable && ifContainer instanceof Variable && returnedValue instanceof Variable) {
                                    final boolean isTarget = OpenapiEquivalenceUtil.areEqual(previousContainer, ifContainer) &&
                                                             OpenapiEquivalenceUtil.areEqual(ifContainer, returnedValue);
                                    if (isTarget) {
                                        result = new Couple<>(
                                                new Couple<>(ifPrevious.getParent(), ifNext),
                                                new Couple<>(ifAssignment.getValue(), previousAssignment.getValue())
                                        );
                                    }
                                }
                            } else if (ifLast instanceof PhpReturn && ifNext instanceof PhpReturn) {
                                final PsiElement lastReturnedValue = ((PhpReturn) ifNext).getArgument();
                                /* assign - if - return - return */
                                if (lastReturnedValue instanceof Variable && ifPrevious instanceof AssignmentExpression) {
                                    final AssignmentExpression previousAssignment = (AssignmentExpression) ifPrevious;
                                    final PsiElement previousContainer            = previousAssignment.getVariable();
                                    if (previousContainer instanceof Variable) {
                                        final boolean isTarget = OpenapiEquivalenceUtil.areEqual(previousContainer, lastReturnedValue);
                                        if (isTarget) {
                                            result = new Couple<>(
                                                    new Couple<>(ifPrevious.getParent(), ifNext),
                                                    new Couple<>(((PhpReturn) ifLast).getArgument(), previousAssignment.getValue())
                                            );
                                        }
                                    }
                                }
                                /* if - return - return */
                                else {
                                    result = new Couple<>(
                                            new Couple<>(statement, ifNext),
                                            new Couple<>(((PhpReturn) ifLast).getArgument(), lastReturnedValue)
                                    );
                                }
                            }
                        }
                    }
                }
                return result;
            }

            @Nullable
            private PsiElement extractCandidate(@Nullable PsiElement statement) {
                if (statement instanceof PhpReturn) {
                    return statement;
                } else if (OpenapiTypesUtil.isStatementImpl(statement)) {
                    final PsiElement possiblyAssignment = statement.getFirstChild();
                    if (OpenapiTypesUtil.isAssignment(possiblyAssignment)) {
                        final AssignmentExpression assignment = (AssignmentExpression) possiblyAssignment;
                        final PsiElement container             = assignment.getVariable();
                        if (container instanceof Variable) {
                            final PsiElement value = assignment.getValue();
                            if (PhpLanguageUtil.isBoolean(value)) {
                                return assignment;
                            }
                        }
                    }
                }
                return null;
            }

            private boolean isTargetCondition(@NotNull PsiElement condition) {
                if (condition instanceof BinaryExpression || condition instanceof PhpIsset || condition instanceof PhpEmpty) {
                    return true;
                } else if (condition instanceof UnaryExpression) {
                    final UnaryExpression unary = (UnaryExpression) condition;
                    if (OpenapiTypesUtil.is(unary.getOperation(), PhpTokenTypes.opNOT)) {
                        final PsiElement argument = ExpressionSemanticUtil.getExpressionTroughParenthesis(unary.getValue());
                        if (argument != null) {
                            return this.isTargetCondition(argument);
                        }
                    }
                } else if (condition instanceof FunctionReference) {
                    return this.isTargetFunction((FunctionReference) condition);
                }
                return false;
            }

            private boolean isTargetFunction(@NotNull FunctionReference reference) {
                final PsiElement resolved = OpenapiResolveUtil.resolveReference(reference);
                if (resolved instanceof Function) {
                    final Function function = (Function) resolved;
                    boolean isTarget        = OpenapiElementsUtil.getReturnType(function) != null;
                    if (!isTarget && function.getName().startsWith("is_")) {
                        final String location = function.getContainingFile().getVirtualFile().getCanonicalPath();
                        isTarget              = location != null && location.contains(".jar!") && location.contains("/stubs/");
                    }
                    return isTarget && function.getType().equals(PhpType.BOOLEAN);
                }
                return false;
            }
        };
    }

    private static final class SimplifyFix implements LocalQuickFix {
        private static final String title = "Use return instead";

        final private SmartPsiElementPointer<PsiElement> from;
        final private SmartPsiElementPointer<PsiElement> to;
        final String replacement;

        SimplifyFix(@NotNull Project project, @NotNull PsiElement from, @NotNull PsiElement to, @NotNull String replacement) {
            super();
            final SmartPointerManager factory = SmartPointerManager.getInstance(project);

            this.from        = factory.createSmartPsiElementPointer(from);
            this.to          = factory.createSmartPsiElementPointer(to);
            this.replacement = replacement;
        }

        @NotNull
        @Override
        public String getName() {
            return title;
        }

        @NotNull
        @Override
        public String getFamilyName() {
            return title;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            final PsiElement from = this.from.getElement();
            final PsiElement to   = this.to.getElement();
            if (from != null && to != null && !project.isDisposed()) {
                final String code = this.replacement + ';';
                if (from == to) {
                    from.replace(PhpPsiElementFactory.createPhpPsiFromText(project, PhpReturn.class, code));
                } else {
                    final PsiElement parent = from.getParent();
                    parent.addBefore(PhpPsiElementFactory.createPhpPsiFromText(project, PhpReturn.class, code), from);
                    parent.deleteChildRange(from, to);
                }
            }
        }
    }
}