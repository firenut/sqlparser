/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package kis.sqlparser;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import kis.sqlparser.SqlAnalizer.SqlValue;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.codehaus.jparsec.OperatorTable;
import org.codehaus.jparsec.Parser;
import org.codehaus.jparsec.Parsers;
import org.codehaus.jparsec.Scanners;
import org.codehaus.jparsec.Terminals;

/**
 *
 * @author naoki
 */
public class SqlParser {
    static final String[] keywords = {
        "between", "and", "or", "select", "from", "left", "join", "on", "where", 
        "insert", "into", "values", "update", "set", "delete", 
        "order", "by", "asc", "desc", "group", "having",
        "create", "table", "index", "using"
    };
    
    static final String[] operators = {
        "=", "<", ">", "<=", ">=", ".", "*", ",", "(", ")", "+", "-", "/"
    };
    
    static final Terminals terms = Terminals.caseInsensitive(operators, keywords);
    static Parser<Void> ignored = Scanners.WHITESPACES.optional();
    static Parser<?> tokenizer = Parsers.or(
            terms.tokenizer(),
            Terminals.Identifier.TOKENIZER,
            Terminals.StringLiteral.SINGLE_QUOTE_TOKENIZER,
            Terminals.IntegerLiteral.TOKENIZER);
    
    public static interface AST{}
    public static interface ASTExp extends AST{}
    // integer
    @AllArgsConstructor @EqualsAndHashCode
    public static class IntValue implements ASTExp, SqlValue{
        int value;
        @Override
        public String toString() {
            return value + "";
        }
    }
    public static Parser<IntValue> integer(){
        return Terminals.IntegerLiteral.PARSER.map(s -> new IntValue(Integer.parseInt(s)));
    }
    
    // identifier
    @AllArgsConstructor @ToString
    public static class ASTIdent implements ASTExp{
        String ident;
    }
    public static Parser<ASTIdent> identifier(){
        return Terminals.Identifier.PARSER.map(s -> new ASTIdent(s));
    }
    
    // str
    @AllArgsConstructor @EqualsAndHashCode
    public static class StringValue implements ASTExp, SqlValue{
        String value;
        @Override
        public String toString() {
            return "'" + value + "'";
        }
        
    }
    public static Parser<StringValue> str(){
        return Terminals.StringLiteral.PARSER.map(s -> new StringValue(
                s.replaceAll("''", "'")));
    }
    
    // fqn := identifier "." identifier
    @AllArgsConstructor @ToString
    public static class ASTFqn implements ASTExp{
        ASTIdent table;
        ASTIdent field;
    }
    public static Parser<ASTFqn> fqn(){
        return identifier().next(t -> terms.token(".").next(identifier()).map(f -> new ASTFqn(t, f)));
    }
    
    @AllArgsConstructor @ToString
    public static class ASTFunc implements ASTExp{
        ASTIdent name;
        List<ASTExp> params;
    }
    
    public static Parser<ASTFunc> func(){
        Parser<ASTFunc> func = identifier().next(id -> 
                value().sepBy(terms.token(",")).between(terms.token("("), terms.token(")"))
                .map(params -> new ASTFunc(id, params)));
        return func;
    }
    
    // value := fqn | identifier | integer | str
    public static Parser<ASTExp> value(){
        return Parsers.or(fqn(), func(), identifier(), integer(), str());
    }
    
    public static Parser<ASTExp> expression(){
        return new OperatorTable<ASTExp>()
                .infixl(terms.token("+").retn((l, r) -> new ASTBinaryOp(l, r, "+")), 10)
                .infixl(terms.token("-").retn((l, r) -> new ASTBinaryOp(l, r, "-")), 10)
                .infixl(terms.token("/").retn((l, r) -> new ASTBinaryOp(l, r, "/")), 20)
                .infixl(terms.token("*").retn((l, r) -> new ASTBinaryOp(l, r, "*")), 20)
                .build(value());
    }
    
    // bicond := value ("=" | "<" | "<=" | ">" | ">=) value
    @AllArgsConstructor @ToString
    public static class ASTBinaryOp implements ASTExp{
        ASTExp left;
        ASTExp right;
        String op;
    }
    
    public static Parser<ASTBinaryOp> bicond(){
        return expression().next(l -> 
                terms.token("=", "<", "<=", ">", ">=").source()
                        .next(op -> 
                expression().map(r -> new ASTBinaryOp(l, r, op))));
    }
    
    // between := value "between" value "and" value
    @AllArgsConstructor @ToString
    public static class ASTTernaryOp implements ASTExp{
        ASTExp obj;
        ASTExp start;
        ASTExp end;
        String op;
    }
    
    public static Parser<ASTTernaryOp> between(){
        return expression().next(o ->
            terms.token("between").next(expression()).next(st -> 
                    terms.token("and").next(expression()).map(ed -> 
                            new ASTTernaryOp(o, st, ed, "between"))));
    }
    // cond := bicond | between
    public static Parser<ASTExp> cond(){
        return Parsers.or(bicond(), between());
    }

    public static Parser<ASTExp> logic(){
        return new OperatorTable<ASTExp>()
                .infixl(terms.token("and").retn((l, r) -> new ASTBinaryOp(l, r, "and")), 1)
                .infixl(terms.token("or").retn((l, r) -> new ASTBinaryOp(l, r, "or")), 1)
                .build(cond());
    }
    
    public static interface ASTStatement extends AST{}
    // select := "select" value ("," value)*
    public static class ASTWildcard implements AST, SqlValue{
        @Override
        public String toString() {
            return "*";
        }
    }
    
    public static Parser<List<? extends AST>> select(){
        return terms.token("select").next(Parsers.or(
                terms.token("*").map(t -> Arrays.asList(new ASTWildcard())), 
                expression().sepBy1(terms.token(","))));
    }
    
    // table := identifier
    // field := identifier | fqn
    // join := "left" "join" table "on" logic
    @AllArgsConstructor @ToString
    public static class ASTJoin implements AST{
        ASTIdent table;
        AST logic;
    }
    
    public static Parser<ASTJoin> join(){
        return terms.phrase("left", "join")
                .next(identifier().next(t -> terms.token("on")
                        .next(logic()).map(lg -> new ASTJoin(t, lg))));
    }
    
    // from := "from" table join*
    @AllArgsConstructor @ToString
    public static class ASTFrom implements AST{
        ASTIdent table;
        List<ASTJoin> joins;
    }
    
    public static Parser<ASTFrom> from(){
        return terms.token("from").next(identifier()
                .next(t -> join().many().map(j -> new ASTFrom(t, j))));
    }
    // where := "where" logic
    public static Parser<ASTExp> where(){
        return terms.token("where").next(logic());
    }
    
    // ordervalue := expression ("asc"|"desc")?
    @AllArgsConstructor @ToString
    public static class ASTOrderValue implements AST{
        ASTExp exp;
        boolean asc;
    }
    public static Parser<ASTOrderValue> orderValue(){
        return expression().next(x -> Parsers.or(
                terms.token("asc").retn(true), 
                terms.token("desc").retn(false)).optional(true)
        .map(b -> new ASTOrderValue(x, b)));
    }
    // orderby := "order" "by" ordervalue ("," ordervalue)*
    public static Parser<List<ASTOrderValue>> orderby(){
        return terms.phrase("order", "by")
                .next(orderValue().sepBy(terms.token(",")));
    }
    
    // group by := "group" "by" ident ("," ident)*
    public static Parser<List<ASTExp>> groupby(){
        return terms.phrase("group", "by")
                .next(Parsers.or(fqn(), identifier())).sepBy1(terms.token(","));
    }
    
    // having := "having" logic
    public static Parser<ASTExp> having(){
        return terms.token("having").next(logic());
    }
    
    // selectStatement := select from where?
    @AllArgsConstructor @ToString
    public static class ASTSelect implements ASTStatement{
        List<? extends AST> select;
        ASTFrom from;
        Optional<? extends AST> where;
        List<ASTExp> groupby;
        Optional<? extends AST> having;
        List<ASTOrderValue> order;
    }
    public static Parser<ASTSelect> selectStatement(){
        return Parsers.sequence(
                select(), from(), where().optional(), 
                groupby().next(g -> having().optional().map(h -> Pair.of(g, h))).optional(),
                orderby().optional(),
                (s, f, w, p, o) -> 
                        new ASTSelect(s, f, Optional.ofNullable(w), 
                                p == null ? Collections.EMPTY_LIST : p.left,
                                p == null ? Optional.empty() : Optional.ofNullable(p.right),
                                o == null ? Collections.EMPTY_LIST : o));
    }

    // insertField := "(" identity ("," identity)* ")"
    public static Parser<List<ASTIdent>> insertField(){
        return Parsers.between(
                terms.token("("), 
                identifier().sepBy1(terms.token(",")) , 
                terms.token(")"));
    }
    
    public static Parser<List<ASTExp>> insertValues(){
        return Parsers.between(
                terms.token("("),
                Parsers.<ASTExp>or(integer(), str()).sepBy1(terms.token(",")), 
                terms.token(")"));
    }
    
    // insert := "insert" "into" table insertField? "values" insertValues ("," insertValues)*
    @AllArgsConstructor @ToString
    public static class ASTInsert implements ASTStatement{
        ASTIdent table;
        Optional<List<ASTIdent>> field;
        List<List<ASTExp>> value;
    }
    
    public static Parser<ASTInsert> insert(){
        return Parsers.sequence(
                terms.phrase("insert", "into").next(identifier()),
                insertField().optional(),
                terms.token("values").next(insertValues().sepBy1(terms.token(","))),
                (tb, f, v) -> new ASTInsert(tb, Optional.ofNullable(f), v));
    }
    
    @AllArgsConstructor
    public static class ASTDelete implements ASTStatement{
        ASTIdent table;
        Optional<AST> where;
    }
    
    public static Parser<ASTDelete> delete(){
        return Parsers.sequence(
                terms.token("delete").next(terms.token("*").optional())
                        .next(terms.token("from")).next(identifier()),
                where().optional(),
                (id, w) -> new ASTDelete(id, Optional.ofNullable(w)));
    }
    
    // insertValue := ident "=" value
    @AllArgsConstructor @ToString
    public static class ASTUpdateValue implements AST{
        ASTIdent field;
        AST value;
    }
    
    public static Parser<ASTUpdateValue> updateValue(){
        return Parsers.sequence(
                identifier(),
                terms.token("=").next(expression()),
                (id, v) -> new ASTUpdateValue(id, v));
    }
    
    // update := "update" table "set" updateValue ("," updateValue)* where
    @AllArgsConstructor @ToString
    public static class ASTUpdate implements ASTStatement{
        ASTIdent table;
        List<ASTUpdateValue> values;
        Optional<AST> where;
    }
    
    public static Parser<ASTUpdate> update(){
        return Parsers.sequence(
                terms.token("update").next(identifier()),
                terms.token("set").next(updateValue().sepBy1(terms.token(","))),
                where().optional(),
                (tbl, values, where) -> new ASTUpdate(tbl, values, Optional.ofNullable(where)));
    }
    
    // createindex := "create" "index" ident? "on" ident ("using" ident)? "(" ident ("," ident)* ")"
    @AllArgsConstructor @ToString
    public static class ASTCreateIndex implements ASTStatement{
        Optional<ASTIdent> indexName;
        Optional<ASTIdent> table;
        Optional<ASTIdent> method;
        List<ASTIdent> field;
    }
    public static Parser<ASTCreateIndex> createIndex(){
        return Parsers.sequence(
            terms.phrase("create", "index").next(identifier().optional()),
            terms.token("on").next(identifier()),
            terms.token("using").next(identifier()).optional(),
            identifier().sepBy(terms.token(",")).between(terms.token("("), terms.token(")")),
            (n, t, m, f) -> new ASTCreateIndex(
                    Optional.ofNullable(n), Optional.of(t), Optional.ofNullable(m), f));
    }
    // createtableField := ident (ident ("(" integer ")")?)?
    
    
    // createtableIndex := "index" ident? ("using" ident)? "(" ident ("," ident)* ")"
    public static Parser<ASTCreateIndex> createTableIndex(){
        return Parsers.sequence(
            terms.token("index").next(identifier().optional()),
            terms.token("using").next(identifier()).optional(),
            identifier().sepBy(terms.token(",")).between(terms.token("("), terms.token(")")),
            (n, m, f) -> new ASTCreateIndex(
                    Optional.ofNullable(n), Optional.empty(), Optional.ofNullable(m), f));
    }
    // createtable := "create" "table" ident "(" ident ("," ident)* ")"
    @AllArgsConstructor @ToString
    public static class ASTCreateTable implements ASTStatement{
        ASTIdent tableName;
        List<ASTIdent> fields;
    }
    public static Parser<ASTCreateTable> createTable(){
        return Parsers.sequence(
            terms.phrase("create", "table").next(identifier()),
                identifier().sepBy1(terms.token(",")).between(terms.token("("), terms.token(")")),
                (t, f) -> new ASTCreateTable(t, f));
    }
    
    public static Parser<ASTStatement> parser(){
        return Parsers.or(selectStatement(), insert(), update(), delete(), createTable(), createIndex()).from(tokenizer, ignored);
    }

}
