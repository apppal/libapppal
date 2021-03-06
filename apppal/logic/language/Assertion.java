package apppal.logic.language;

import apppal.Util;
import apppal.logic.evaluation.Substitution;
import apppal.logic.evaluation.Unification;
import apppal.logic.grammar.AppPALEmitter;
import apppal.logic.grammar.AppPALLexer;
import apppal.logic.grammar.AppPALParser;
import apppal.logic.interfaces.EntityHolding;
import apppal.logic.interfaces.Unifiable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

/** SecPAL Assertion */
public class Assertion implements EntityHolding, Unifiable<Assertion> {
  public final E speaker;
  public final Claim says;
  private final int scope;

  private static int number = 0;

  public Assertion(E speaker, Claim says) {
    this(speaker, says, ++Assertion.number);
  }

  public Assertion(E speaker, Claim says, int scope) {
    this.speaker = speaker;
    this.says = says;
    this.scope = scope;
    this.scope(scope);

    this.expandTypes();

    for (Fact f : this.says.antecedents) f.implicitSpeaker = speaker;
  }

  private void expandTypes() {
    /* Expand all the typing rules into SecPAL facts */
    final Set<Variable> vs = this.vars();
    //Util.debug("found "+vs.size()+" vars");
    for (final Variable v : vs) {
      final String typingObligation = v.obligeTyping();
      if (typingObligation != null) {
        Util.debug("found obligation that: " + typingObligation);
        try {
          final Fact obligation = Fact.parse(typingObligation);
          obligation.subject.scope = v.scope;
          for (Fact f : this.says.antecedents) {
            if (f.toString().equals(obligation.toString()))
              throw new DuplicatedTypeException(typingObligation);
          }
          this.says.antecedents.add(obligation);
        } catch (IOException err) {
          Util.error("couldn't expand typing obligation that " + typingObligation + ": " + err);
        } catch (DuplicatedTypeException err) {
          Util.warn("in assertion:");
          Util.warn("  " + this);
          Util.warn("the typing obligation:");
          Util.warn("  " + typingObligation);
          Util.warn("is already satisfied");
          Util.warn("we're going to ignore the obligation");
          Util.warn("remove the duplicated type declaration to get rid of this warning");
        }
      }
    }
  }

  public class DuplicatedTypeException extends Exception {
    public DuplicatedTypeException(String message) {
      super(message);
    }
  }

  public boolean isCanActAs() {
    return (says.consequent.object instanceof CanActAs);
  }

  public boolean isCanSay() {
    return (says.consequent.object instanceof CanSay);
  }

  public boolean isGround() {
    return this.vars().size() == 0;
  }

  public Set<Variable> vars() {
    Set<Variable> vars = this.speaker.vars();
    vars.addAll(this.says.vars());
    return vars;
  }

  public Set<Constant> consts() {
    Set<Constant> consts = this.speaker.consts();
    consts.addAll(this.says.consts());
    return consts;
  }

  /**
   * Create an assertion by parsing a string
   *
   * @param str the assertion to parse
   * @returns the parsed assertion
   */
  public static Assertion parse(String str) throws IOException {
    InputStream in = new ByteArrayInputStream(str.getBytes("UTF-8"));
    ANTLRInputStream input = new ANTLRInputStream(in);
    AppPALLexer lexer = new AppPALLexer(input);
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    AppPALParser parser = new AppPALParser(tokens);
    ParseTree tree = parser.assertion();
    AppPALEmitter emitter = new AppPALEmitter();
    return (Assertion) emitter.visit(tree);
  }

  public String toString() {
    return this.speaker + " says " + this.says + ".";
  }

  /**
   * @brief Check whether the assertion meets the SecPAL safety conditions.
   *     <p>An assertion a (A says f if f1,...,fn where c) is safe if:
   *     <p>1. a) if f is flat => all v in vars(f) are safe in a. b) if f is (e can-say ...) => e is
   *     safe in a. 2. vars c are a subset of the vars of the consequent and antecedent. 3. all
   *     antecedent facts are flat.
   * @returns boolean
   */
  public boolean isSafe() {
    // 1. a) if f is flat => all v in vars(f) are safe in a.
    if (this.says.consequent.isFlat()) {
      for (E e : this.says.consequent.vars())
        if (!e.safeIn(this)) {
          Util.warn("the variable:");
          Util.warn("  " + e);
          Util.warn("in assertion:");
          Util.warn("  " + this);
          Util.warn("violates safety condition 1a");
          return false;
        }
    }
    //   b) if f is (e can-say ...) => e is safe in a.
    else {
      assert (this.says.consequent.object instanceof CanSay);
      if (!this.says.consequent.subject.safeIn(this)) return false;
    }

    // 2. vars c are a subset of the vars of the consequent and antecedent.
    Set<Variable> c_vars = this.says.constraint.vars();
    Set<Variable> o_vars = this.says.consequent.vars();
    o_vars.addAll(this.says.antecedentVars());
    if (!o_vars.containsAll(c_vars)) return false;

    // 3. all antecedent facts are flat.
    if (this.says.hasAntecedents())
      for (Fact f : this.says.antecedents)
        if (!f.isFlat()) {
          return false;
        }

    return true;
  }

  @Override
  public Unification unify(Assertion that) {
    final Unification unification = this.speaker.unify(that.speaker);
    if (!unification.hasFailed()) {
      Claim thetaX = this.says.substitute(unification.theta);
      Claim thetaY = that.says.substitute(unification.theta);
      unification.compose(thetaX.unify(thetaY));
    }
    return unification;
  }

  @Override
  public Assertion substitute(Map<Variable, Substitution> delta) {
    final Claim says = this.says.substitute(delta);
    return new Assertion(speaker, says);
  }

  public Set<Constant> getVoiced() {
    Set<Constant> voiced = new HashSet<>();
    if (this.speaker instanceof Constant) voiced.add((Constant) this.speaker);

    /* // If we never have any statements from this delegated speaker why bother to search?
    if ((this.says.consequent.object instanceof CanSay)
      && (this.says.consequent.subject instanceof Constant))
      voiced.add((Constant) this.says.consequent.subject);
    */
    return voiced;
  }

  public Set<Constant> getSubjects() {
    Set<Constant> subjects = new HashSet<>();
    if (this.says.consequent.subject instanceof Constant)
      subjects.add((Constant) this.says.consequent.subject);

    for (Fact f : this.says.antecedents)
      if (f.subject instanceof Constant) subjects.add((Constant) f.subject);

    return subjects;
  }

  /**
   * When writing tests it is helpful to be able to reset the global assertion counter so we know
   * which assertions have which scopes. THIS SHOULD NEVER BE CALLED IN THE REAL WORLD.
   */
  public static void resetScope() {
    Assertion.number = 0;
  }

  private void scope(int scope) {
    this.speaker.scope(scope);
    this.says.scope(scope);
  }

  public Assertion consequence() {
    return new Assertion(this.speaker, new Claim(this.says.consequent), this.scope);
  }

  // TODO: refactor into constructors
  public static Assertion makeCanActAs(E speaker, E subject, Constant c) {
    return Assertion.make(speaker, subject, new CanActAs(c));
  }

  public static Assertion make(E speaker, E c, VP object) {
    return Assertion.make(speaker, new Fact(c, object));
  }

  public static Assertion make(E speaker, Fact consequent) {
    return new Assertion(speaker, new Claim(consequent));
  }

  public static Assertion makeCanSay(E speaker, Constant c, D d, Fact fact) {
    return Assertion.make(speaker, c, new CanSay(d, fact));
  }

  /* Extracts all the predicates used in the assertion */
  public Set<String> getPredicates() {
    final Set<String> predicates = new HashSet<String>();
    String predicate = this.says.consequent.getPredicate();
    if (predicate != null) predicates.add(predicate);

    for (final Fact f : this.says.antecedents) {
      predicate = f.getPredicate();
      if (predicate != null) predicates.add(f.getPredicate());
    }

    return predicates;
  }
}
