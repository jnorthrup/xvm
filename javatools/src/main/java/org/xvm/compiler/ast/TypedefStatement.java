package org.xvm.compiler.ast;


import java.lang.reflect.Field;

import org.xvm.asm.Component;
import org.xvm.asm.Constants.Access;
import org.xvm.asm.ErrorListener;
import org.xvm.asm.MethodStructure.Code;
import org.xvm.asm.TypedefStructure;

import org.xvm.asm.constants.TypeConstant;

import org.xvm.compiler.Compiler;
import org.xvm.compiler.Token;
import org.xvm.compiler.Token.Id;

import org.xvm.util.Severity;


/**
 * A typedef statement specifies a type to alias as a simple name.
 */
public class TypedefStatement
        extends ComponentStatement {
    // ----- constructors --------------------------------------------------------------------------

    public TypedefStatement(Expression cond, Token keyword, TypeExpression type, Token alias) {
        super(keyword.getStartPosition(), alias.getEndPosition());

        this.cond     = cond;
        this.modifier = keyword.getId() == Id.TYPEDEF ? null : keyword;
        this.type     = type;
        this.alias    = alias;
    }

    /**
     * Construct a parameterized typedef: {@code typedef Body as Name<F1,F2,...>}.
     *
     * @param cond        the optional condition expression
     * @param keyword     the "typedef" keyword (or an access modifier token)
     * @param type        the body type expression
     * @param alias       the new typedef name token
     * @param typeParams  the formal type parameter tokens (e.g. {@code [A, B]})
     */
    public TypedefStatement(Expression cond, Token keyword, TypeExpression type, Token alias,
                            Token[] typeParams) {
        super(keyword.getStartPosition(), alias.getEndPosition());

        this.cond        = cond;
        this.modifier    = keyword.getId() == Id.TYPEDEF ? null : keyword;
        this.type        = type;
        this.alias       = alias;
        this.typeParams  = typeParams;
    }

    /**
     * @return the formal type parameter name tokens, or null if the typedef is not parameterized
     */
    public Token[] getTypeParams() {
        return typeParams;
    }


    // ----- accessors -----------------------------------------------------------------------------

    @Override
    public Access getDefaultAccess() {
        if (modifier != null) {
            switch (modifier.getId()) {
            case PUBLIC:
                return Access.PUBLIC;
            case PROTECTED:
                return Access.PROTECTED;
            case PRIVATE:
                return Access.PRIVATE;
            }
        }

        return super.getDefaultAccess();
    }

    @Override
    protected Field[] getChildFields() {
        return CHILD_FIELDS;
    }


    // ----- compile phases ------------------------------------------------------------------------

    @Override
    protected void registerStructures(StageMgr mgr, ErrorListener errs) {
        // create the structure for this method
        if (getComponent() == null) {
            // create a structure for this typedef
            Component container = getParent().getComponent();
            String    sName     = (String) alias.getValue();
            if (container != null && container.isClassContainer()) {
                Access           access    = getDefaultAccess();
                TypeConstant     constType = type.ensureTypeConstant();
                TypedefStructure typedef   = container.createTypedef(access, constType, sName);
                if (typeParams != null && typeParams.length > 0) {
                    String[] names = new String[typeParams.length];
                    for (int i = 0; i < typeParams.length; i++) {
                        names[i] = (String) typeParams[i].getValue();
                    }
                    typedef.setTypeParamNames(names);
                }
                setComponent(typedef);
            } else if (!errs.hasSeriousErrors()) {
                log(errs, Severity.ERROR, Compiler.TYPEDEF_UNEXPECTED, sName, container);
            }
        }
    }

    @Override
    protected Statement validateImpl(Context ctx, ErrorListener errs) {
        return this;
    }

    @Override
    protected boolean emit(Context ctx, boolean fReachable, Code code, ErrorListener errs) {
        return true;
    }

    // ----- debugging assistance ------------------------------------------------------------------

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        if (cond != null) {
            sb.append("if (")
              .append(cond)
              .append(") { ");
        }

        if (modifier != null) {
            sb.append(modifier)
              .append(' ');
        }

        sb.append("typedef ")
          .append(type)
          .append(' ')
          .append(alias.getValue())
          .append(';');

        if (cond != null) {
            sb.append(" }");
        }

        return sb.toString();
    }

    @Override
    public String getDumpDesc() {
        return toString();
    }


    // ----- fields --------------------------------------------------------------------------------

    protected Expression     cond;
    protected Token          modifier;
    protected Token          alias;
    protected TypeExpression type;
    protected Token[]        typeParams;

    private static final Field[] CHILD_FIELDS = fieldsForNames(TypedefStatement.class, "cond", "type");
}