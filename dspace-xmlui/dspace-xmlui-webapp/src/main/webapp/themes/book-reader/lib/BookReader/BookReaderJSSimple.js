//
// This file shows the minimum you need to provide to BookReader to display a book
//
// Copyright(c)2008-2009 Internet Archive. Software license AGPL version 3.

$(document).ready(function() {
    // Create the BookReader object
    br = new BookReader();

    // Return the width of a given page.  Here we assume all images are 800 pixels wide
    br.getPageWidth = function(index) {
        return 800;
    }

    // Return the height of a given page.  Here we assume all images are 1200 pixels high
    br.getPageHeight = function(index) {
        return 1200;
    }

    // We load the images from archive.org -- you can modify this function to retrieve images
    // using a different URL structure
    br.getPageURI = function(index) {
        return $("table.file-list tr.ds-table-row td:first-child a").eq(index).attr("href");
        //$("table.file-list tr.ds-table-row td:first-child a").each(function(){console.log($(this).attr("href"))});
    }

    // Return which side, left or right, that a given page should be displayed on
    br.getPageSide = function(index) {
        //TODO - We have no way to know which side a book starts on
        if (0 == (index & 0x1)) {
            return 'R';
        } else {
            return 'L';
        }
    }

    // This function returns the left and right indices for the user-visible
    // spread that contains the given index.  The return values may be
    // null if there is no facing page or the index is invalid.
    br.getSpreadIndices = function(pindex) {
        var spreadIndices = [null, null];
        if ('rl' == this.pageProgression) {
            // Right to Left
            if (this.getPageSide(pindex) == 'R') {
                spreadIndices[1] = pindex;
                spreadIndices[0] = pindex + 1;
            } else {
                // Given index was LHS
                spreadIndices[0] = pindex;
                spreadIndices[1] = pindex - 1;
            }
        } else {
            // Left to right
            if (this.getPageSide(pindex) == 'L') {
                spreadIndices[0] = pindex;
                spreadIndices[1] = pindex + 1;
            } else {
                // Given index was RHS
                spreadIndices[1] = pindex;
                spreadIndices[0] = pindex - 1;
            }
        }

        return spreadIndices;
    }

    // For a given "accessible page index" return the page number in the book.
    //
    // For example, index 5 might correspond to "Page 1" if there is front matter such
    // as a title page and table of contents.
    br.getPageNum = function(index) {
        return index+1;
    }

    // Total number of leafs
    br.numLeafs = $("table.file-list tr.ds-table-row td:first-child a").length;

    // Book title and the URL used for the book title link
    br.bookTitle= document.title;
    br.bookUrl  = $('meta[name=DC.identifier][scheme=DCTERMS.URI]').attr('content');

    // Let's go!
    br.init();

});
