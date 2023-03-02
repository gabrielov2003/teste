#  Copyright 2023 cms.rendner (Daniel Schmidt)
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
from plugin_code.custom_json_encoder import CustomJSONEncoder
from plugin_code.html_props_generator import HTMLPropsGenerator
from plugin_code.html_props_table_builder import HTMLPropsTable
from plugin_code.html_props_table_generator import HTMLPropsTableGenerator
from plugin_code.patched_styler_context import PatchedStylerContext, Region
from plugin_code.style_function_name_resolver import StyleFunctionNameResolver
from plugin_code.style_functions_validator import StyleFunctionsValidator, StyleFunctionValidationProblem, \
    ValidationStrategyType
from plugin_code.todos_patcher import TodosPatcher

# == copy after here ==
import json
from dataclasses import dataclass
from typing import List, Optional, Any
import numpy as np


@dataclass(frozen=True)
class TableStructure:
    org_rows_count: int
    org_columns_count: int
    rows_count: int
    columns_count: int
    row_levels_count: int
    column_levels_count: int
    hide_row_header: bool
    hide_column_header: bool


@dataclass(frozen=True)
class StyleFunctionDetails:
    index: int
    qname: str
    resolved_name: str
    axis: str
    is_chunk_parent_requested: bool
    is_apply: bool
    is_pandas_builtin: bool
    is_supported: bool


class PatchedStyler:
    def __init__(self, context: PatchedStylerContext):
        self.__context: PatchedStylerContext = context

    def internal_get_context(self) -> PatchedStylerContext:
        return self.__context

    def get_org_indices_of_visible_columns(self, part_start: int, max_columns: int) -> str:
        part = self.__context.get_org_indices_of_visible_columns(part_start, max_columns)
        return np.array2string(part, separator=', ').replace('\n', '')

    @staticmethod
    def to_json(data: Any) -> str:
        return json.dumps(data, cls=CustomJSONEncoder)

    def validate_style_functions(self,
                                 first_row: int,
                                 first_col: int,
                                 rows: int,
                                 cols: int,
                                 validation_strategy: Optional[ValidationStrategyType] = None,
                                 ) -> List[StyleFunctionValidationProblem]:
        return StyleFunctionsValidator(self.__context, validation_strategy)\
            .validate(Region(first_row, first_col, rows, cols))

    def set_sort_criteria(self,
                          by_column_index: Optional[List[int]] = None,
                          ascending: Optional[List[bool]] = None,
                          ):
        self.__context.set_sort_criteria(by_column_index, ascending)

    def compute_chunk_html_props_table(self,
                                       first_row: int,
                                       first_col: int,
                                       rows: int,
                                       cols: int,
                                       exclude_row_header: bool = False,
                                       exclude_col_header: bool = False
                                       ) -> HTMLPropsTable:
        return HTMLPropsTableGenerator(HTMLPropsGenerator(self.__context)).compute_chunk_table(
            region=Region(first_row, first_col, rows, cols),
            exclude_row_header=exclude_row_header,
            exclude_col_header=exclude_col_header,
        )

    def internal_compute_unpatched_html_props_table(self) -> HTMLPropsTable:
        return HTMLPropsTableGenerator(HTMLPropsGenerator(self.__context)).internal_compute_unpatched_table()

    def get_table_structure(self) -> TableStructure:
        visible_frame = self.__context.get_visible_frame()
        styler = self.__context.get_styler()
        org_rows_count = len(styler.data.index)
        org_columns_count = len(styler.data.columns)
        rows_count = len(visible_frame.index)
        columns_count = len(visible_frame.columns)
        if rows_count == 0 or columns_count == 0:
            rows_count = columns_count = 0
        return TableStructure(
            org_rows_count=org_rows_count,
            org_columns_count=org_columns_count,
            rows_count=rows_count,
            columns_count=columns_count,
            row_levels_count=visible_frame.index.nlevels - styler.hide_index_.count(True),
            column_levels_count=visible_frame.columns.nlevels - styler.hide_columns_.count(True),
            hide_row_header=all(styler.hide_index_),
            hide_column_header=all(styler.hide_columns_)
        )

    def get_style_function_details(self) -> List[StyleFunctionDetails]:
        result = []

        for i, todo in enumerate(self.__context.get_styler_todos()):
            result.append(StyleFunctionDetails(
                index=i,
                qname=StyleFunctionNameResolver.get_style_func_qname(todo),
                resolved_name=StyleFunctionNameResolver.resolve_style_func_name(todo),
                axis='' if todo.is_applymap() else str(todo.apply_args.axis),
                is_pandas_builtin=todo.is_pandas_style_func(),
                is_supported=TodosPatcher.is_style_function_supported(todo),
                is_apply=not todo.is_applymap(),
                is_chunk_parent_requested=todo.should_provide_chunk_parent(),
            ))

        return result